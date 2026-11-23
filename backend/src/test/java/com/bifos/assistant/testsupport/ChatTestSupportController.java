package com.bifos.assistant.testsupport;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.time.Clock;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저와 e2e 검사가 대화를 준비하게 돕는다.
 *
 * <p>대화 번호는 주소와 API 에 나오지 않아 브라우저 검사가 얻을 길이 없다. 새 대화는 기본 비서로만 열리므로
 * (docs/features/assistant-hub/spec.md 의 REQ4) 다른 에이전트의 실행을 보는 검사는 그 에이전트와의 대화를 여기서 만든다. 비서가 아닌
 * 대화는 읽기 전용이라(REQ5) 그 에이전트를 잠시 기본 비서로 두고, 검사가 끝나면 {@link PrimaryAgentTestSupportController#restore}
 * 로 되돌린다.
 */
@RestController
@RequestMapping("/api/v1/test-support/chat")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")
public class ChatTestSupportController {

    private final EntityManager entityManager;
    private final CurrentUserProvider currentUser;
    private final AgentService agents;
    private final ConversationRepository conversations;
    private final Clock clock;
    private final PrimaryAgentTestSupportController primaries;


    /**
     * 공개 식별자로 대화 번호를 찾는다.
     *
     * <p>부르는 사람의 대화인지 보지 않는다. 남의 대화로 옛 주소를 여는 검사도 이 번호를 쓴다.
     * 운영 저장소에는 사용자를 함께 받는 조회만 있어, 운영 코드에 조회를 더하지 않고 여기서 JPQL 로 찾는다.
     */
    @GetMapping("/conversations/{conversationId}/number")
    public ResponseEntity<ConversationNumber> number(@PathVariable UUID conversationId) {
        return entityManager
            .createQuery("select c.id from Conversation c where c.publicId = :id", Long.class)
            .setParameter("id", conversationId)
            .getResultList()
            .stream()
            .findFirst()
            .map(number -> ResponseEntity.ok(new ConversationNumber(number)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }


    /**
     * 부르는 사람과 그 에이전트의 빈 대화를 만들고 그 에이전트를 잠시 기본 비서로 둔다.
     *
     * <p>쓸 수 없거나 꺼진 에이전트는 운영의 시작 판정과 같은 오류다.
     */
    @PostMapping("/conversations")
    public StartedConversation start(@RequestBody StartRequest request) {
        CurrentUser user = currentUser.require();
        Agent agent = agents.requireStartable(user, request.agentCode());
        // 비서가 아닌 대화는 읽기 전용이라(REQ5) 그 에이전트를 잠시 기본 비서로 둔다. 검사가 끝나면 restore 가 되돌린다.
        primaries.useTemporarily(user, agent);
        Conversation saved = conversations.save(Conversation.startedBy(user.id(), "", agent.id(), clock.instant()));
        return new StartedConversation(saved.publicId());
    }


    /**
     * 부르는 사람의 실행 하나를 그 에이전트가 돈 것으로 바꾼다. {@code @} 맡기기를 없애기 전에 남은 맡김과 같은 꼴을 만든다
     * (docs/features/assistant-hub/spec.md 의 REQ6). 대화의 에이전트가 아닌 루트 실행이 맡김이다.
     */
    @PutMapping("/executions/{executionId}/agent/{agentCode}")
    @Transactional
    public ResponseEntity<Void> relabel(@PathVariable Long executionId, @PathVariable String agentCode) {
        CurrentUser user = currentUser.require();
        Agent agent = agents.requireReadable(user, agentCode);
        int changed = entityManager
            .createQuery("update AgentExecution e set e.agentId = :agentId where e.id = :id and e.userId = :userId")
            .setParameter("agentId", agent.id())
            .setParameter("id", executionId)
            .setParameter("userId", user.id())
            .executeUpdate();
        return changed == 1 ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }


    /** 대화를 열 에이전트 코드다. */
    public record StartRequest(String agentCode) {
    }

    /** 만든 대화의 공개 식별자다. */
    public record StartedConversation(UUID conversationId) {
    }

    /** 대화 표의 번호다. */
    public record ConversationNumber(Long number) {
    }
}
