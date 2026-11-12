package com.bifos.assistant.chat.nextturn.application;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.nextturn.application.dto.DelegationFinished;
import com.bifos.assistant.chat.nextturn.application.dto.DelegationStarted;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.DelegationLines;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 대화의 turn 이 바로 맡긴 위임이 시작하고 끝날 때 그 대화를 보는 모든 창에 {@code delegation} 사건을 보낸다
 * (docs/features/agent-team/design.md 의 「PR2 협업 보이기」).
 *
 * <p>위임은 turn 의 SSE 와 다른 스레드에서 돌고 turn 이 끝난 뒤에도 끝나므로 대화 단위 SSE 로만 보낸다. 손자 위임은 보내지 않는다.
 * 보내다 실패해도 위임과 결과 전달에는 영향이 없다. 화면은 이력을 다시 읽어 줄을 맞춘다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DelegationLineNotifier {
    private final DelegationLines lines;
    private final ConversationRepository conversations;
    private final ConversationEventHub hub;


    @EventListener
    public void onStarted(DelegationStarted event) {
        publish(event.conversationId(), event.executionId());
    }


    /** 끝난 줄을 위임 결과 자동 turn 의 사건보다 먼저 보내려고 다음 turn 을 정하는 리스너보다 앞에 둔다. */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onFinished(DelegationFinished event) {
        publish(event.conversationId(), event.executionId());
    }


    private void publish(Long conversationId, Long executionId) {
        if (conversationId == null || executionId == null) {
            return;
        }
        try {
            lines.directDelegation(executionId)
                .ifPresent(line -> conversations.findById(conversationId)
                    .map(Conversation::publicId)
                    .ifPresent(publicId -> hub.publish(conversationId, ChatEvent.delegation(publicId, line))));
        } catch (RuntimeException ex) {
            log.warn(
                "맡긴 줄의 상태를 화면에 알리지 못했다 executionId={} kind={}",
                executionId,
                ex.getClass().getSimpleName());
        }
    }
}
