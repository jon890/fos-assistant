package com.bifos.assistant.attention.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionConfidence;
import com.bifos.assistant.attention.application.model.AttentionSourceRef;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 요청자의 최근 대화를 이어서 하기 카드의 후보로 낸다.
 *
 * <p>최근 대화를 {@code continueCount + maxItemsPerCard} 개만 읽는다. 다른 카드와 겹친 대화가 빠져도 상한을 채울 만큼 읽으려는
 * 것이다. 그래서 이 카드의 {@code moreCount} 는 읽은 범위 안에서 센 수다.
 */
@Component
@RequiredArgsConstructor
public class RecentConversationCandidates implements AttentionCandidates {

    private final ChatService chats;
    private final AgentService agents;
    private final AttentionProperties properties;

    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.CONTINUE);
    }

    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        List<Conversation> recent = chats.conversationsOf(
                        user, null, properties.continueCount() + properties.maxItemsPerCard())
                .items();
        Map<Long, Agent> agentById =
                agents.byIds(recent.stream().map(Conversation::agentId).toList());
        return recent.stream()
                .map(conversation -> candidate(conversation, agentById.get(conversation.agentId())))
                .toList();
    }

    private static AttentionCandidate candidate(Conversation conversation, Agent agent) {
        String itemKey = "conversation:" + conversation.publicId();
        return new AttentionCandidate(
                CardKey.CONTINUE,
                itemKey,
                AttentionCandidates.stateKey(
                        AttentionTrigger.CONVERSATION_RECENT,
                        conversation.updatedAt().toString()),
                AttentionTrigger.CONVERSATION_RECENT,
                false,
                false,
                List.of(),
                AttentionConfidence.CONTROL_PLANE,
                conversation.title(),
                conversation.publicId(),
                agent == null ? null : agent.name(),
                conversation.updatedAt(),
                List.of(new AttentionSourceRef("CONVERSATION", itemKey, conversation.updatedAt())),
                null,
                null,
                null);
    }
}
