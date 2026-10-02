package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * turn 을 열지 않고 대화에 알림 줄만 남긴다.
 *
 * <p>승인 요청의 거절과 만료처럼 모델에게 바로 전할 필요가 없는 일을 사용자에게 알린다. 그 줄은 이력에 남아 다음
 * 질문 때 모델도 본다.
 */
@Service
@RequiredArgsConstructor
public class ConversationNotices {

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ConversationEventHub hub;

    /** 알림 줄을 저장하고 열린 화면에 알린다. 대화가 없거나 지워졌으면 아무것도 하지 않는다. */
    public void post(Long conversationId, String text) {
        conversations
                .findById(conversationId)
                .filter(conversation -> conversation.deletedAt() == null)
                .ifPresent(conversation -> {
                    ChatMessage saved = messages.save(ChatMessage.fromSystem(conversation.id(), text));
                    hub.publish(conversation.id(), ChatEvent.system(conversation.publicId(), saved.id(), text));
                });
    }

    /** 화면에 보이는 대화 식별자다. 대화가 없거나 지워졌으면 비어 있다. */
    public Optional<UUID> publicIdOf(Long conversationId) {
        return conversations
                .findById(conversationId)
                .filter(conversation -> conversation.deletedAt() == null)
                .map(conversation -> conversation.publicId());
    }
}
