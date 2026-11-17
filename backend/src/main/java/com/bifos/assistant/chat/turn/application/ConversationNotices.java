package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import java.time.Clock;
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
    private final Clock clock;


    /** 알림 줄을 저장하고 열린 화면에 알린다. 대화가 없거나 지워졌으면 아무것도 하지 않는다. */
    public void post(Long conversationId, String text) {
        save(conversationId, text).ifPresent(saved -> publish(conversationId, saved));
    }


    /**
     * 알림 줄만 저장한다. 부르는 쪽의 트랜잭션이 있으면 그 안에서 저장해, 다른 쓰기와 함께 남거나 함께 빠진다. 알리기는 커밋한 뒤
     * {@link #publish} 로 한다.
     *
     * @return 저장한 줄. 대화가 없거나 지워졌으면 비어 있다
     */
    public Optional<ChatMessage> save(Long conversationId, String text) {
        return conversations
            .findById(conversationId)
            .filter(conversation -> conversation.deletedAt() == null)
            .map(conversation -> messages.save(ChatMessage.fromSystem(conversation.id(), text, clock.instant())));
    }


    /** 저장한 알림 줄을 열린 화면에 알린다. 대화가 없거나 지워졌으면 아무것도 하지 않는다. */
    public void publish(Long conversationId, ChatMessage saved) {
        publicIdOf(conversationId)
            .ifPresent(publicId -> hub.publish(conversationId, ChatEvent.system(publicId, saved.id(), saved.content())));
    }


    /** 화면에 보이는 대화 식별자다. 대화가 없거나 지워졌으면 비어 있다. */
    public Optional<UUID> publicIdOf(Long conversationId) {
        return conversations
            .findById(conversationId)
            .filter(conversation -> conversation.deletedAt() == null)
            .map(conversation -> conversation.publicId());
    }
}
