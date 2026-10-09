package com.bifos.assistant.proactive.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 실행 하나가 먼저 살펴보기 트리 안에 있는지 정한다(ADR-080).
 *
 * <p>그 실행의 트리 루트({@link AgentExecution#treeRootId()})가 {@code proactive_check.root_execution_id} 에 있으면 살펴보기
 * 트리다. 커넥터 도구 판정, Control Plane MCP, 위임이 이 판정으로 읽기 경계를 건다. 쓰기 도구를 허용한 살펴보기(ADR-082)는
 * 커넥터 판정과 Control Plane MCP 의 경계가 넓어진다. 경계는 {@code docs/features/proactive.md} 의 「읽기 경계」 가 갖는다.
 */
@Component
@RequiredArgsConstructor
public class ProactiveCheckGuard {

    private final ProactiveCheckRepository checks;
    private final LiveProperties<ProactiveCheckProperties> properties;
    private final ConversationRepository conversations;

    /** 그 실행의 트리 루트가 살펴보기 turn 인가. */
    public boolean isCheckTree(AgentExecution execution) {
        return checks.existsByRootExecutionId(execution.treeRootId());
    }

    /** 그 대화가 그 살펴보기의 점검 대화인가. 쓰기 도구를 허용한 살펴보기의 결과물 쓰기를 그 대화로 묶는다(ADR-082). */
    public boolean isCheckConversation(ProactiveCheck check, UUID conversationPublicId) {
        return conversations
                .findById(check.conversationId())
                .map(Conversation::publicId)
                .filter(conversationPublicId::equals)
                .isPresent();
    }

    /**
     * 그 실행이 속한 살펴보기. 살펴보기 트리가 아니면 빈 값이다. 위임 판정이 그 살펴보기의 상태를 보고, 커넥터 판정과 Control Plane
     * MCP 는 이 줄 하나로 트리인지와 쓰기 허용을 함께 본다.
     */
    public Optional<ProactiveCheck> checkOf(AgentExecution execution) {
        return checks.findByRootExecutionId(execution.treeRootId());
    }

    /** 살펴보기 트리 하나에서 맡길 수 있는 위임 자식 수. 끝난 자식도 센다. */
    public int maxDelegations() {
        return properties.current().maxDelegations();
    }
}
