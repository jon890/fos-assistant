package com.bifos.assistant.proactive.application;

import com.bifos.assistant.chat.application.RestartReconciler;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 서버가 도중에 내려가 {@code RUNNING} 으로 남은 먼저 살펴보기를 기동할 때 닫는다(ADR-080). 규칙은
 * {@code docs/backend/proactive-check.md} 의 「끝날 때」 가 갖는다.
 *
 * <p>그 줄을 {@code FAILED}, {@code error_code = INTERRUPTED} 로 적고, 루트 실행이 있으면 그 트리의 위임 결과를 전했다고 적은 뒤
 * {@link ProactiveCheckEnded} 를 낸다. {@code orchestration} 이 받아 그 트리의 도는 위임 자식을 멈춘다. 기동 때는 이 서버가 돌리는
 * 위임이 없으므로 run 번호가 있는 자식에 Hermes 중지만 보낸다. 다시 붙는 루트 turn 자체는 멈추지 않는다. 그 turn 은
 * {@code hermes.run-timeout} 까지 돌 수 있다. 대화에는 알림 줄을 남기지 않는다.
 *
 * <p>{@link RestartReconciler} 보다 먼저 돈다. 그 정리가 끝낸 위임 자식이나 이미 끝나 있던 위임 자식이 점검 대화에 읽기 경계
 * 밖의 자동 turn 을 열지 않게 하기 위해서다. 그래서 그 정리보다 작은 lifecycle 단계에서 시작한다. 그 정리를 빈으로 받으면 Spring
 * 이 의존하는 빈을 먼저 시작하므로 받지 않고 단계 값만 읽는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveCheckRecovery implements SmartLifecycle {

    /** 기동할 때 닫은 살펴보기 줄의 {@code error_code} 다. */
    static final String INTERRUPTED = "INTERRUPTED";

    private final ProactiveCheckRepository checks;
    private final ExecutionDeliveryWriter deliveryWriter;
    private final ApplicationEventPublisher events;
    private final CheckFeedback feedback;
    private final Clock clock;

    /** 이 프로세스에서 이미 닫았다. 다시 시작할 때 도는 살펴보기는 이 프로세스의 것이라 닫지 않는다. */
    private final AtomicBoolean recovered = new AtomicBoolean(false);

    private volatile boolean running;

    @Override
    public int getPhase() {
        return RestartReconciler.PHASE - 1;
    }

    /** 처음 시작할 때만 남은 줄을 닫는다. 예외가 나도 기동을 실패시키지 않는다. */
    @Override
    public void start() {
        if (recovered.compareAndSet(false, true)) {
            try {
                int closed = closeInterrupted(clock.instant());
                if (closed > 0) {
                    log.info("기동 전에 돌던 먼저 살펴보기 {}건을 끊긴 것으로 닫았다", closed);
                }
            } catch (RuntimeException ex) {
                log.error("기동 전에 돌던 먼저 살펴보기를 닫지 못했다", ex);
            }
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * {@code RUNNING} 줄을 하나씩 닫는다. 한 줄의 실패는 그 줄만 남긴다.
     *
     * @return 닫은 줄 수
     */
    private int closeInterrupted(Instant now) {
        int closed = 0;
        for (ProactiveCheck check : checks.findByStatus(CheckStatus.RUNNING)) {
            try {
                close(check, now);
                closed++;
            } catch (RuntimeException ex) {
                log.warn("기동 전에 돌던 먼저 살펴보기를 닫지 못했다 checkId={}", check.id(), ex);
                continue;
            }
            if (check.rootExecutionId() != null) {
                stopChildren(check);
            }
        }
        return closed;
    }

    /** 그 트리의 도는 위임 자식을 멈추라는 사건을 낸다. 실패해도 다음 줄로 넘어간다. 줄은 이미 닫혔다. */
    private void stopChildren(ProactiveCheck check) {
        try {
            events.publishEvent(new ProactiveCheckEnded(check.rootExecutionId()));
        } catch (RuntimeException ex) {
            log.warn(
                    "닫은 살펴보기 트리의 위임 자식을 멈추지 못했다 checkId={} rootExecutionId={}",
                    check.id(),
                    check.rootExecutionId(),
                    ex);
        }
    }

    /**
     * 전달 표시를 먼저 적고 줄을 닫는다. 줄을 먼저 닫고 표시에서 실패하면 다음 기동이 그 줄을 다시 보지 않아 자동 turn 이 열린다. 거꾸로
     * 줄을 닫다 실패하면 다음 기동이 다시 닫는다.
     */
    private void close(ProactiveCheck check, Instant now) {
        if (check.rootExecutionId() != null) {
            deliveryWriter.markTreeDelivered(check.rootExecutionId(), now);
        }
        check.fail(INTERRUPTED, check.toolCalls(), check.delegations(), now);
        checks.save(check);
        feedback.ended(check);
    }
}
