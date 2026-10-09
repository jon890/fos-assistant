package com.bifos.assistant.task.application;

import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동 전에 돌던 발화를 {@code FAILED}({@code INTERRUPTED})로 닫고 알린다(ADR-077).
 *
 * <p>다시 돌리지 않는다. 쓰기가 두 번 일어날 수 있다. {@code QUEUED} 줄은 그대로 두고 다음 tick 이 연다. 규칙은 {@code
 * backend/docs/flow.md} 의 「기동할 때」 가 갖는다.
 *
 * <p>{@link TaskDispatcher} 는 이 정리가 끝난 뒤에야 돈다. 그 전에 연 줄을 정리가 닫지 않게 하기 위해서다. 정리가 예외로
 * 끝나도 끝난 것으로 적는다. 적지 않으면 다시 띄울 때까지 어떤 작업도 발화하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRunRecovery {

    private final TaskRunRepository runs;
    private final TaskRepository tasks;
    private final TaskNotices notices;
    private final ProactiveCheckRepository checks;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** 기동 정리가 끝났는가. 끝나기 전에는 발화기가 돌지 않는다. */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    /** 끊긴 실행 기록의 정리와 대화 깨우기 뒤에 돈다. 예외가 나도 기동을 실패시키지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    public void onReady() {
        try {
            int closed = closeInterrupted(clock.instant());
            if (closed > 0) {
                log.info("기동 전에 돌던 예약 작업 발화 {}건을 끊긴 것으로 닫았습니다", closed);
            }
        } catch (RuntimeException ex) {
            log.error("기동 전에 돌던 예약 작업 발화를 닫지 못했다", ex);
        } finally {
            finished.set(true);
        }
    }

    /** 기동 정리가 끝났으면 true 다. */
    public boolean finished() {
        return finished.get();
    }

    /**
     * {@code RUNNING} 줄을 하나씩 잠그고 다시 읽어 닫는다. 한 줄의 실패는 그 줄만 남긴다.
     *
     * @return 닫은 줄 수
     */
    private int closeInterrupted(Instant now) {
        int closed = 0;
        for (TaskRun running : runs.findByStatusOrderByScheduledForAscIdAsc(TaskRunStatus.RUNNING)) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> close(running.id(), now)))) {
                    closed++;
                }
            } catch (RuntimeException ex) {
                log.warn("기동 전에 돌던 예약 작업 발화를 닫지 못했다 taskRunId={}", running.id(), ex);
            }
        }
        return closed;
    }

    private boolean close(Long runId, Instant now) {
        TaskRun run = runs.findByIdForUpdate(runId).orElse(null);
        if (run == null || run.status() != TaskRunStatus.RUNNING) {
            return false;
        }
        if (run.proactiveCheckId() != null) {
            var check = checks.findById(run.proactiveCheckId()).orElse(null);
            if (check != null && check.status() != CheckStatus.RUNNING) {
                if (check.skippedReason() != null) {
                    run.skip(TaskRunReason.UNREAD_REPORT, now);
                } else if (check.status() == CheckStatus.SUCCEEDED) {
                    run.succeed(check.rootExecutionId(), now);
                } else {
                    run.fail(TaskRunReason.FAILED, now);
                }
                notices.announce(tasks.findById(run.taskId()).orElseThrow(), run);
                return true;
            }
        }
        run.fail(TaskRunReason.INTERRUPTED, now);
        notices.announce(tasks.findById(run.taskId()).orElseThrow(), run);
        return true;
    }
}
