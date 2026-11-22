package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.orchestration.application.dto.DelegationWait;
import com.bifos.assistant.orchestration.config.DelegationProperties;
import com.bifos.assistant.proactive.check.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 대화 turn 의 루트 실행이 {@code agent_delegate} 로 맡긴 일을 그 도구 호출 안에서 기다린다(ADR-20261121 / delegate-wait).
 *
 * <p>한 답에서 기다리는 시간은 그 루트가 처음 기다리기 시작한 때부터 흐른 시간으로 세고, 상한은
 * {@link DelegationProperties#delegateWaitMax()} 다. Hermes 는 Control Plane 도구를 하나씩 부르므로 같은 답의 맡김은 차례로 같은
 * 마감까지 기다린다(스펙 assistant-hub DECISION20). 손자 위임과 먼저 살펴보기 트리는 기다리지 않는다(DECISION11).
 *
 * <p>사용자가 루트 turn 을 중지하면(중지 표시가 서면) 곧바로 기다림을 푼다. 그때 끝난 결과를 이 호출이 전했다고 적으면 받을 모델이 이미 멈춰 결과가
 * 어디에도 전해지지 않으므로 {@link DelegationWait#deliverable()} 를 거짓으로 둔다.
 *
 * <p>예산은 프로세스 메모리에 둔다. 루트 잠금과 중지 표시처럼 서버 하나를 전제로 한다. 트랜잭션을 걸지 않는다. 기다리는 동안 DB
 * 연결을 쥐지 않고, 다시 읽은 줄이 같은 영속 컨텍스트의 옛 엔티티가 되지 않게 하기 위해서다.
 */
@Component
@RequiredArgsConstructor
public class DelegationWaiter {

    /** 중지 표시를 다시 보는 간격이다. 중지는 이 시간 안에 기다림을 푼다. */
    static final Duration POLL = Duration.ofSeconds(1);

    private final AgentDelegationService delegations;
    private final TurnCancellation turns;
    private final ProactiveCheckGuard checkGuard;
    private final LiveProperties<DelegationProperties> properties;
    private final AgentExecutionRepository executions;
    private final Clock clock;

    /** 기다리지 않았다는 결과다. 부르는 쪽은 지금 응답을 그대로 준다. */
    private static final DelegationWait NOT_WAITED = new DelegationWait(null, false, false);

    /** 루트 실행 번호마다 첫 기다림을 시작한 시각이다. */
    private final Map<Long, Instant> startedAt = new ConcurrentHashMap<>();


    /**
     * 맡긴 실행이 끝나기를 남은 예산만큼 기다린 뒤 다시 읽는다.
     *
     * @param origin 이 {@code agent_delegate} 호출의 origin 실행
     * @param executionId 방금 맡긴 실행
     */
    public DelegationWait await(AgentExecution origin, Long executionId) {
        if (origin.parentExecutionId() != null || checkGuard.isCheckTree(origin)) {
            return NOT_WAITED;
        }
        Duration max = properties.current().delegateWaitMax();
        if (max.isZero()) {
            return NOT_WAITED;
        }
        if (sameProfile(origin, executionId)) {
            // Hermes 는 profile 마다 Control Plane 도구 호출을 하나씩 보낸다. 맡은 실행이 같은 profile 이면 그 실행의 도구 호출이
            // 이 기다림 뒤에 서서 끝날 수 없다(hermes/plugins/fos-ctx/README.md 「호출은 profile 마다 하나씩 나간다」)
            return NOT_WAITED;
        }
        Long rootId = origin.treeRootId();
        Duration remaining = remaining(rootId, max);
        RunningDelegation delegation = delegations.running(executionId);
        // 예산을 다 쓴 같은 답의 맡김과 이 서버가 돌리지 않는 맡김은 기다리지 않고 지금 상태를 준다
        Instant deadline = clock.instant().plus(remaining);
        while (delegation != null && !remaining.isZero() && !turns.isCancelled(rootId) && !Thread.currentThread().isInterrupted()) {
            Duration left = Duration.between(clock.instant(), deadline);
            if (left.isNegative() || left.isZero() || delegation.awaitEnded(left.compareTo(POLL) < 0 ? left : POLL)) {
                break;
            }
        }
        AgentExecution execution = executions.findById(executionId).orElse(null);
        boolean ended = execution != null && execution.status() != ExecutionStatus.RUNNING;
        return new DelegationWait(execution, ended && !turns.isCancelled(rootId), true);
    }


    private boolean sameProfile(AgentExecution origin, Long executionId) {
        return executions.findById(executionId)
            .map(child -> child.profileName() != null && child.profileName().equals(origin.profileName()))
            .orElse(false);
    }


    /**
     * 그 루트가 한 답 안에서 더 기다릴 수 있는 시간이다. 처음 부르면 지금을 시작으로 적는다.
     *
     * <p>마감에서 상한만큼 더 지난 항목은 이때 지운다. 그만큼 지났으면 그 답은 이미 끝났다.
     */
    Duration remaining(Long rootId, Duration max) {
        if (max == null || max.isZero() || max.isNegative()) {
            return Duration.ZERO;
        }
        Instant now = clock.instant();
        startedAt.values().removeIf(started -> started.plus(max.multipliedBy(2)).isBefore(now));
        Instant started = startedAt.computeIfAbsent(rootId, ignored -> now);
        Duration left = Duration.between(now, started.plus(max));
        return left.isNegative() ? Duration.ZERO : left;
    }
}
