package com.bifos.assistant.chat.nextturn.application;

import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageStored;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 비서가 만든 그림이 저장되면 그 대화를 보는 모든 창에 {@code image} 사건을 보낸다(docs/features/generated-image/design.md 의 「PR7」).
 *
 * <p>그림은 turn 의 스트림과 다른 올리기 요청에서 저장되고 turn 밖에서도 오므로 대화 단위 SSE 로만 보낸다. 보내다 실패해도 올리기는
 * 성공한다. 화면은 답이 끝나 이력을 다시 읽을 때 그림을 본다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GeneratedImageLineNotifier {

    private final ConversationRepository conversations;
    private final ConversationEventHub hub;


    @EventListener
    public void onStored(GeneratedImageStored event) {
        try {
            conversations.findById(event.conversationId())
                .map(Conversation::publicId)
                .ifPresent(publicId -> hub.publish(event.conversationId(), ChatEvent.image(publicId, event.path())));
        } catch (RuntimeException ex) {
            log.warn(
                "만든 그림을 화면에 알리지 못했다 conversationId={} kind={}",
                event.conversationId(),
                ex.getClass().getSimpleName());
        }
    }
}
