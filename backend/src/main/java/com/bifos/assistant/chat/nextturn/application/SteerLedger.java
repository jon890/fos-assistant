package com.bifos.assistant.chat.nextturn.application;

import com.bifos.assistant.chat.conversation.application.PendingMessageStore;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.PendingMessages;
import com.bifos.assistant.chat.conversation.domain.type.SteerChild;
import com.bifos.assistant.chat.conversation.domain.type.SteerState;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.turn.application.dto.SteerView;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * steer 말풍선의 상태와 대기 줄의 steer 칸을 바꾸는 자리다. steer 상태 규칙의 주인은 여기 하나다(ADR-20261116 / run-steer).
 *
 * <p>{@link NextTurnDispatcher} 를 주입하지 않는다. turn 을 돌리는 쪽이 이것을 부르므로, 주입하면 빈 순환이 생긴다. 다음 turn 은
 * 부르는 쪽이 정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SteerLedger {

    private final PendingMessageStore pendingMessages;
    private final ChatMessageRepository messages;
    private final ConversationEventHub hub;
    private final TransactionTemplate transactions;
    private final Clock clock;


    /**
     * Hermes 가 받았다. 대기 글을 빼고 「전달됨」 말풍선을 한 트랜잭션에 저장한다.
     *
     * @param executionId 끼워 넣은 turn 의 루트 실행
     */
    public void accept(
        Conversation conversation, Long pendingId, String text, Long userId, Long executionId, SteerChild child) {
        ChatMessage saved = transactions.execute(status -> {
            removePending(conversation.id(), pendingId);
            return messages.save(ChatMessage.fromSteer(
                conversation.id(), userId, text, executionId, SteerState.DELIVERED, child, clock.instant()));
        });
        publishSteer(conversation, saved);
        hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
    }


    /** Hermes 가 받지 않았다. 「끼우는 중」 표시만 지워 그 글이 지금 대기열로 가게 한다. */
    public void reject(Conversation conversation, Long pendingId) {
        pendingMessages.change(conversation.id(), queue -> queue.withSteering(pendingId, false));
        hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
    }


    /**
     * Hermes 가 받았는지 모른다. 「전달됐는지 확인 못 함」 말풍선을 남기고 같은 글을 말풍선 번호를 붙여 줄 앞에 되돌려 멈춘다. 같은 지시가
     * 두 번 실행되지 않게 사람이 고른다(스펙 DECISION23).
     */
    public void unknown(Conversation conversation, Long pendingId, String text, Long userId, Long executionId) {
        ChatMessage saved = transactions.execute(status -> {
            removePending(conversation.id(), pendingId);
            ChatMessage message = messages.save(ChatMessage.fromSteer(
                conversation.id(), userId, text, executionId, SteerState.UNCONFIRMED, null, clock.instant()));
            pendingMessages.change(conversation.id(), queue -> queue
                .prependCarried(List.of(new PendingMessages.Carried(message.id(), text)), clock.instant())
                .withHeld(true));
            return message;
        });
        publishSteer(conversation, saved);
        hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
    }


    /** 대기 글을 뺀다. 없으면 받은 응답과 어긋난 것이라 경고만 남긴다. 「끼우는 중」 글은 취소를 막아 생기지 않아야 한다. */
    private void removePending(Long conversationId, Long pendingId) {
        pendingMessages.change(conversationId, queue -> {
            if (queue.find(pendingId).isEmpty()) {
                log.warn("끼운 글이 대기 줄에 없다 conversationId={} pendingId={}", conversationId, pendingId);
                return queue;
            }
            return queue.without(List.of(pendingId));
        });
    }


    void publishSteer(Conversation conversation, ChatMessage message) {
        hub.publish(
            conversation.id(),
            ChatEvent.steer(
                conversation.publicId(), message.id(), message.content(), message.steerExecutionId(), SteerView.of(message)));
    }
}
