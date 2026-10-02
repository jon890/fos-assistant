package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * turn 이 닫힐 때, 위임이 끝났을 때, 서버가 뜰 때 다음 turn 을 정한다.
 *
 * <p>다음 turn 을 정하는 자리는 이것 하나다(ADR-048). 리스너를 여럿 걸면 같은 순간에 turn 잠금을 다투고 순서가 등록
 * 순서에 달리기 때문이다. 잠금은 {@link TurnCancellation} 의 메모리 맵이라 서버 하나를 전제로 한다.
 *
 * <p>대기 메시지를 먼저 보고, 보낼 것이 없으면 끝난 위임 결과를 본다. 사용자의 말이 위임 결과보다 먼저 간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NextTurnDispatcher {

    /**
     * 보내려다 실패했는데 대기 행을 멈추지도 못한 대화에서 대기 메시지 turn 을 다시 열지 않고 기다리는 시간이다.
     *
     * <p>행이 멈추지 않은 채 남으므로, 기다리지 않으면 그 turn 을 닫는 자리에서 같은 행으로 곧바로 다시 열어 같은 실패를
     * 쉬지 않고 되풀이한다. 이 시간이 지난 뒤의 turn 닫기, 위임 종료 사건, 더하기, 기동 확인이 다시 시도한다.
     */
    static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);

    private final TurnCancellation turns;
    private final DelegationWakeService wake;
    private final ChatPendingMessageRepository pendingMessages;
    private final ConversationRepository conversations;
    private final AppUserRepository users;
    private final ChatService chat;
    private final ConversationEventHub hub;
    private final TransactionTemplate transactions;

    private final Clock clock = Clock.systemUTC();

    /** 대기 행을 멈추지 못한 대화와 그 시각이다. 메모리에만 두며 서버가 다시 뜨면 사라진다. */
    private final Map<Long, Instant> unheldFailures = new ConcurrentHashMap<>();

    @PostConstruct
    void listenToTurnClose() {
        turns.addCloseListener(this::onTurnClosed);
    }

    /**
     * 중지로 닫힌 turn 뒤에는 대기 줄이 멈췄다고 화면에 알린다.
     *
     * <p>멈춤 표시는 {@link ChatService} 가 잠금을 풀기 전에 이미 적었다. 여기서 적으면 잠금이 풀린 뒤라 그 사이 다른
     * 스레드가 아직 멈추지 않은 행으로 turn 을 연다.
     *
     * <p>알리다 실패해도 다음 turn 은 정한다. 알림 때문에 위임 결과 깨우기까지 건너뛰지 않는다.
     */
    void onTurnClosed(TurnClosed closed) {
        Long conversationId = closed.conversationId();
        if (closed.stopped()) {
            try {
                notifyHeld(conversationId);
            } catch (RuntimeException ex) {
                log.warn("대기 줄이 멈췄다고 알리지 못했다 conversationId={}", conversationId, ex);
            }
        }
        tryNext(conversationId);
    }

    private void notifyHeld(Long conversationId) {
        if (pendingMessages.findByConversationIdOrderByIdAsc(conversationId).isEmpty()) {
            return;
        }
        conversations
                .findById(conversationId)
                .ifPresent(conversation -> hub.publish(conversationId, ChatEvent.pending(conversation.publicId())));
    }

    @EventListener
    public void onDelegationFinished(DelegationFinished event) {
        if (event.conversationId() != null) {
            tryNext(event.conversationId());
        }
    }

    /**
     * 기동 전에 보내지 못한 대기 메시지나 전하지 못한 결과가 있는 대화를 차례로 이어 준다.
     *
     * <p>{@link RestartReconciler} 의 묻기가 시작한 뒤에 돈다. run 번호가 없어 그때 FAILED 로 적힌 위임 실행의 결과는
     * 이 깨우기가 전한다. {@link RestartReconciler} 가 turn 잠금을 잡은 대화는 건너뛰고, 그 잠금이 풀릴 때 닫기
     * 리스너로 다시 온다. 멈춰 둔 대기 줄은 그대로 둔다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void dispatchAfterStartup() {
        Set<Long> conversationIds = new LinkedHashSet<>(pendingMessages.findConversationsReadyToSend());
        conversationIds.addAll(wake.conversationsToWake());
        for (Long conversationId : conversationIds) {
            try {
                tryNext(conversationId);
            } catch (RuntimeException ex) {
                // 한 대화가 실패해도 나머지 대화는 이어 준다.
                log.warn("기동 뒤 대화를 깨우지 못했다 conversationId={}", conversationId, ex);
            }
        }
    }

    public void tryNext(Long conversationId) {
        if (tryPending(conversationId)) {
            return;
        }
        wake.tryWake(conversationId);
    }

    /**
     * 보낼 대기 메시지가 있으면 turn 잠금을 잡고 새 가상 스레드에서 보낸다.
     *
     * <p>대기 행을 멈추지 못한 대화는 {@link #FAILURE_BACKOFF} 동안 열지 않고 거짓을 돌려준다. 그동안에도 위임 결과는
     * 전한다.
     *
     * @return 대기 메시지가 이 대화의 다음 turn 을 차지했다. 도는 turn 때문에 잠금을 잡지 못한 때도 참이다. 그
     *     turn 이 닫힐 때 다시 온다
     */
    private boolean tryPending(Long conversationId) {
        List<ChatPendingMessage> rows = pendingMessages.findByConversationIdOrderByIdAsc(conversationId);
        if (rows.isEmpty() || rows.stream().anyMatch(ChatPendingMessage::held)) {
            return false;
        }
        if (inFailureBackoff(conversationId)) {
            return false;
        }
        Optional<Conversation> found = conversations.findById(conversationId);
        if (found.isEmpty() || found.get().deletedAt() != null) {
            return false;
        }
        Conversation conversation = found.get();
        Optional<AppUser> user = users.findById(conversation.userId());
        if (user.isEmpty()) {
            log.warn("대화 주인이 없어 대기 메시지를 보내지 않는다 conversationId={}", conversationId);
            return false;
        }
        AppUser owner = user.get();
        TurnCancellation.TurnHandle handle;
        try {
            handle = turns.open(owner.id(), conversationId);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.CONVERSATION_BUSY) {
                return true;
            }
            throw ex;
        }
        CurrentUser current =
                new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
        List<Long> ids = rows.stream().map(ChatPendingMessage::id).toList();
        try {
            Thread.ofVirtual()
                    .name("pending-turn-" + conversationId)
                    .start(() -> runQueuedTurn(current, conversation, handle, ids));
        } catch (RuntimeException | Error ex) {
            log.warn("대기 메시지 turn 스레드를 띄우지 못했다 conversationId={}", conversationId, ex);
            turns.close(handle);
        }
        return true;
    }

    /**
     * 대기 메시지 turn 을 돌리고 잠금을 푼다.
     *
     * <p>사용자 메시지를 저장하기 전에 실패하면 대기 행이 그대로 남는다. 잠금을 풀기 전에 그 행을 멈춰 둔다. 그대로
     * 닫으면 닫기 리스너가 같은 행으로 곧바로 다시 열어 같은 실패를 되풀이한다. 멈추지도 못하면 잠금을 풀기 전에 실패
     * 시각을 적어 한동안 다시 열지 않는다.
     */
    private void runQueuedTurn(
            CurrentUser owner, Conversation conversation, TurnCancellation.TurnHandle handle, List<Long> ids) {
        Long conversationId = conversation.id();
        try {
            chat.runPendingMessages(owner, conversationId, handle, event -> hub.publish(conversationId, event));
            unheldFailures.remove(conversationId);
        } catch (ApiException ex) {
            log.warn("대기 메시지 turn 이 실패했다 conversationId={} code={}", conversationId, ex.code(), ex);
            hub.publish(conversationId, ChatEvent.error(ex.code().name(), ex.getMessage()));
            holdUnsent(conversation, ids);
        } catch (RuntimeException ex) {
            log.error("대기 메시지 turn 이 예외로 끝났다 conversationId={}", conversationId, ex);
            hub.publish(conversationId, ChatEvent.error(ErrorCode.INTERNAL_ERROR.name(), "internal error"));
            holdUnsent(conversation, ids);
        } finally {
            turns.close(handle);
        }
    }

    /** 이 turn 이 보내려던 행이 하나라도 남아 있으면 그 대화의 대기 줄을 멈춰 둔다. 보낸 뒤의 실패는 남은 행이 없다. */
    private void holdUnsent(Conversation conversation, List<Long> ids) {
        try {
            boolean unsent = pendingMessages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
                    .anyMatch(row -> ids.contains(row.id()));
            if (!unsent) {
                return;
            }
            transactions.executeWithoutResult(status -> pendingMessages.markHeld(conversation.id(), true));
            hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()));
        } catch (RuntimeException ex) {
            // 남았는지 모르거나 멈추지 못했다. 쉬지 않고 다시 여는 것보다 잠시 늦게 보내는 편이 낫다.
            log.warn("보내지 못한 대기 메시지를 멈춰 두지 못했다 conversationId={}", conversation.id(), ex);
            unheldFailures.put(conversation.id(), Instant.now(clock));
        }
    }

    private boolean inFailureBackoff(Long conversationId) {
        Instant failedAt = unheldFailures.get(conversationId);
        if (failedAt == null) {
            return false;
        }
        if (failedAt.plus(FAILURE_BACKOFF).isAfter(Instant.now(clock))) {
            return true;
        }
        unheldFailures.remove(conversationId, failedAt);
        return false;
    }
}
