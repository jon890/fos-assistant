package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.skill.application.ActiveConversationPublicIds;
import com.bifos.assistant.usage.application.ConversationPublicIds;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 대화 번호를 공개 식별자로 바꾼다.
 *
 * <p>사용량과 스킬 쪽이 대화 저장소를 직접 쓰지 않도록 두 port 를 함께 구현한다. 트랜잭션을 열지 않아 조회
 * 하나가 자기 트랜잭션으로 돈다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작하므로 번호가 비면 읽지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
public class ConversationPublicIdLookup implements ConversationPublicIds, ActiveConversationPublicIds {

    private final ConversationRepository conversations;

    @Override
    public Map<Long, UUID> publicIdsOf(Collection<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        return conversations.findAllById(conversationIds).stream()
                .collect(Collectors.toMap(Conversation::id, Conversation::publicId));
    }

    @Override
    public Map<Long, UUID> activePublicIdsOf(Collection<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        return conversations.findAllById(conversationIds).stream()
                .filter(conversation -> conversation.deletedAt() == null)
                .collect(Collectors.toMap(Conversation::id, Conversation::publicId));
    }
}
