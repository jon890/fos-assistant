package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.proactive.application.ProactiveCheckEnded;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 먼저 살펴보기가 끝났다는 사건을 받아 그 트리의 도는 위임 자식을 멈춘다(ADR-077).
 *
 * <p>{@code proactive} 는 {@code orchestration} 을 부르지 않고 사건을 낸다. 위임 결과로 부모 대화를 깨울 때와 같은 방향이다.
 */
@Component
@RequiredArgsConstructor
public class ProactiveCheckEndedListener {

    private final AgentDelegationService delegations;

    @EventListener
    public void onProactiveCheckEnded(ProactiveCheckEnded event) {
        if (event.rootExecutionId() != null) {
            delegations.stopRunningChildrenOf(event.rootExecutionId());
        }
    }
}
