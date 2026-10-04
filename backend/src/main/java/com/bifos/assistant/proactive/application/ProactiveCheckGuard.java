package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 실행 하나가 먼저 살펴보기 트리 안에 있는지 정한다(ADR-077).
 *
 * <p>그 실행의 트리 루트({@link AgentExecution#treeRootId()})가 {@code proactive_check.root_execution_id} 에 있으면 살펴보기
 * 트리다. 커넥터 도구 판정, Control Plane MCP, 위임이 이 판정으로 읽기 경계를 건다. 경계는
 * {@code docs/backend/proactive-check.md} 의 「읽기 경계」 가 갖는다.
 */
@Component
@RequiredArgsConstructor
public class ProactiveCheckGuard {

    private final ProactiveCheckRepository checks;
    private final ProactiveCheckProperties properties;

    /** 그 실행의 트리 루트가 살펴보기 turn 인가. */
    public boolean isCheckTree(AgentExecution execution) {
        return checks.existsByRootExecutionId(execution.treeRootId());
    }

    /** 그 실행이 속한 살펴보기. 살펴보기 트리가 아니면 빈 값이다. 위임 판정이 그 살펴보기의 상태를 본다. */
    public Optional<ProactiveCheck> checkOf(AgentExecution execution) {
        return checks.findByRootExecutionId(execution.treeRootId());
    }

    /** 살펴보기 트리 하나에서 맡길 수 있는 위임 자식 수. 끝난 자식도 센다. */
    public int maxDelegations() {
        return properties.maxDelegations();
    }
}
