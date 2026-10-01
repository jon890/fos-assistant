package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.orchestration.application.FlowRegistry;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final FlowRegistry flows;
    private final ChatPendingMessageRepository pendingMessages;
    private final ConversationEventHub hub;
    private final NextTurnDispatcher dispatcher;
    private final TransactionTemplate transactions;

    private final Clock clock = Clock.systemUTC();

    /**
     * 상한 확인과 저장을 대화마다 차례로 하게 하는 잠금이다.
     *
     * <p>메모리에 있어 turn 잠금과 같이 서버 하나를 전제로 한다. 가상 스레드가 잠금 안에서 DB 를 부르므로
     * {@code synchronized} 가 아니라 {@link ReentrantLock} 을 쓴다.
     */
    private final ConcurrentHashMap<Long, ReentrantLock> addLocks = new ConcurrentHashMap<>();

    public PendingQueue queue(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        return queueOf(conversation.id());
    }

    /**
     * 대기 메시지를 더한다. 도는 turn 이 없으면 곧바로 보낸다.
     *
     * <p>turn 이 도는지 먼저 보지 않고 언제나 저장한 뒤 다음 turn 을 정하는 자리를 부른다. 먼저 보고 정하면 보는
     * 순간과 저장하는 순간 사이에 turn 이 닫혔을 때 그 글을 보낼 계기가 없다.
     */
    public PendingQueue enqueue(CurrentUser user, Long conversationId, String text) {
        Conversation conversation = access.requireOwn(user, conversationId);
        requireQueueable(conversation);
        ReentrantLock lock = addLocks.computeIfAbsent(conversation.id(), id -> new ReentrantLock());
        lock.lock();
        try {
            List<ChatPendingMessage> rows = pendingMessages.findByConversationIdOrderByIdAsc(conversation.id());
            if (rows.size() >= MAX_ITEMS || ChatPendingMessage.mergedLength(rows, text) > MAX_MERGED_CHARS) {
                throw new ApiException(ErrorCode.PENDING_QUEUE_FULL, "the pending queue is full");
            }
            // 멈춘 줄에 더한 글만 보내지 않게 새 행도 멈춘 채로 넣는다.
            boolean held = rows.stream().anyMatch(ChatPendingMessage::held);
            pendingMessages.save(
                    ChatPendingMessage.queued(conversation.id(), user.id(), text, held, Instant.now(clock)));
        } finally {
            lock.unlock();
        }
        hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
        dispatcher.tryNext(conversation.id());
        return queueOf(conversation.id());
    }

    /** 대기 메시지 하나를 취소한다. 이미 보내졌거나 없으면 {@code PENDING_MESSAGE_NOT_FOUND} 다. */
    public void cancel(CurrentUser user, Long conversationId, Long pendingId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        Integer deleted = transactions.execute(status -> pendingMessages.deleteOne(pendingId, conversation.id()));
        if (deleted == null || deleted == 0) {
            throw new ApiException(ErrorCode.PENDING_MESSAGE_NOT_FOUND, "no such pending message");
        }
        hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
    }

    /** 멈춰 둔 대기 줄을 풀어 보낸다. turn 이 돌고 있으면 그 turn 이 끝난 뒤 간다. */
    public PendingQueue release(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        transactions.executeWithoutResult(status -> pendingMessages.markHeld(conversation.id(), false));
        hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
        dispatcher.tryNext(conversation.id());
        return queueOf(conversation.id());
    }

    /** 보통 보내기와 같은 순서와 메시지로 거절한다. 흐름이 붙은 에이전트는 대기 메시지를 받지 않는다. */
    private void requireQueueable(Conversation conversation) {
        Agent agent = agents.requireById(conversation.agentId());
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        if (flows.find(agent.flow()) != null) {
            throw new ApiException(ErrorCode.CONVERSATION_BUSY, "this conversation does not take queued messages");
        }
    }

    private PendingQueue queueOf(Long conversationId) {
        List<ChatPendingMessage> rows = pendingMessages.findByConversationIdOrderByIdAsc(conversationId);
        return new PendingQueue(rows.stream().anyMatch(ChatPendingMessage::held), rows);
    }
}
