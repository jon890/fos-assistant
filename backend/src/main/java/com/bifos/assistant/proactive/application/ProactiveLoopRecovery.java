package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.proactive.infra.ProactiveLoopRunRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서버가 멈춰 끝나지 않은 매일 루프 시도를 기동 때 {@code FAILED}, {@code INTERRUPTED} 로 닫는다(ADR-20261008 / daily-loop).
 *
 * <p>평가와 판정을 다시 부르지 않는다. {@code RUNNING} 줄에는 평가 번호가 없으므로 닫은 줄의 평가 번호도 비어 있다. 평가가 시작됐는지는
 * 같은 원천 살펴보기의 평가 줄로 찾는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProactiveLoopRecovery implements SmartLifecycle {

    static final String INTERRUPTED = "INTERRUPTED";

    private final ProactiveLoopRunRepository runs;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private volatile boolean running;

    public void recover() {
        List<ProactiveLoopRun> stale;
        try {
            stale = runs.findByStatus(LoopRunStatus.RUNNING);
        } catch (RuntimeException ex) {
            log.warn("복구할 매일 루프 시도 목록을 읽지 못했다 error={}", ex.getClass().getSimpleName());
            return;
        }
        for (ProactiveLoopRun row : stale) {
            try {
                close(row.id());
            } catch (RuntimeException ex) {
                log.warn(
                        "매일 루프 시도의 기동 복구를 건너뛴다 runId={} error={}",
                        row.id(),
                        ex.getClass().getSimpleName());
            }
        }
    }

    /** 새 트랜잭션에서 다시 읽어, 그사이 끝나지 않았을 때만 닫는다. */
    private void close(Long runId) {
        transactions.executeWithoutResult(status -> runs.findById(runId)
                .filter(row -> row.status() == LoopRunStatus.RUNNING)
                .ifPresent(row -> row.failed(INTERRUPTED, null, clock.instant())));
    }

    @Override
    public void start() {
        try {
            recover();
        } catch (RuntimeException ex) {
            log.warn("매일 루프 기동 복구가 실패해도 서버 기동을 이어 간다 error={}", ex.getClass().getSimpleName());
        } finally {
            running = true;
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
