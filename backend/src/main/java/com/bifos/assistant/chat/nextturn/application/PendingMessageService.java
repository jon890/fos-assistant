package com.bifos.assistant.chat.nextturn.application;

import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.turn.application.dto.PendingQueue;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.application.PendingMessageStore;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * turn 이 도는 동안 보낸 글을 대기 줄에 쌓고, 취소하고, 멈춘 줄을 푼다(ADR-048).
 *
 * <p>보내는 것은 {@link NextTurnDispatcher} 가 맡는다. 여기서는 줄을 바꾼 뒤 그 자리를 부르기만 한다.
 */
@Service
@RequiredArgsConstructor
public class PendingMessageService {

    static final int MAX_ITEMS = 5;

    /** 합친 글이 사용자 메시지 하나로 저장되므로 메시지 길이 상한과 같다. */
    static final int MAX_MERGED_CHARS = 8000;

    private final ConversationAccess access;
    private final AgentService agents;
    private final PendingMessageStore pendingMessages;
    private final ConversationEventHub hub;
    private final NextTurnDispatcher dispatcher;

    private final Clock clock = Clock.systemUTC();


    public PendingQueue queue(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        return queueOf(conversation.id());
    }


    /**
     * 대기 메시지를 더한다. 도는 turn 이 없으면 곧바로 보낸다.
     *
     * <p>turn 이 도는지 먼저 보지 않고 언제나 저장한 뒤 다음 turn 을 정하는 자리를 부른다. 먼저 보고 정하면 보는
     * 순간과 저장하는 순간 사이에 turn 이 닫혔을 때 그 글을 보낼 계기가 없다.
     *
     * <p>상한 확인과 저장은 대화 줄을 잠근 한 트랜잭션이라, 같은 대화에 동시에 더해도 상한을 넘지 않는다. 멈춘 줄에 더한 글은
     * 멈춘 채로 들어간다.
     */
    public PendingQueue enqueue(CurrentUser user, Long conversationId, String text) {
        Conversation conversation = access.requireOwn(user, conversationId);
        requireQueueable(conversation);
        pendingMessages.change(conversation.id(), queue -> {
            if (queue.items().size() >= MAX_ITEMS || queue.mergedLength(text) > MAX_MERGED_CHARS) {
                throw new ApiException(ErrorCode.PENDING_QUEUE_FULL, "the pending queue is full");
            }
            return queue.append(text, Instant.now(clock));
        });
        hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
        dispatcher.tryNext(conversation.id());
        return queueOf(conversation.id());
    }


    /** 대기 메시지 하나를 취소한다. 이미 보내졌거나 없으면 {@code PENDING_MESSAGE_NOT_FOUND} 다. */
    public void cancel(CurrentUser user, Long conversationId, Long pendingId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        pendingMessages.change(conversation.id(), queue -> {
            if (!queue.containsAll(List.of(pendingId))) {
                throw new ApiException(ErrorCode.PENDING_MESSAGE_NOT_FOUND, "no such pending message");
            }
            return queue.without(List.of(pendingId));
        });
        hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
    }


    /** 멈춰 둔 대기 줄을 풀어 보낸다. turn 이 돌고 있으면 그 turn 이 끝난 뒤 간다. */
    public PendingQueue release(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        pendingMessages.hold(conversation.id(), false);
        hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
        dispatcher.tryNext(conversation.id());
        return queueOf(conversation.id());
    }


    /** 보통 보내기와 같은 순서와 메시지로 거절한다. */
    private void requireQueueable(Conversation conversation) {
        Agent agent = agents.requireById(conversation.agentId());
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }

    }


    private PendingQueue queueOf(Long conversationId) {
        return PendingQueue.of(pendingMessages.read(conversationId));
    }
}
