package com.bifos.assistant.chat.nextturn.application;

import com.bifos.assistant.chat.conversation.application.PendingMessageStore;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.PendingMessage;
import com.bifos.assistant.chat.conversation.domain.PendingMessages;
import com.bifos.assistant.chat.conversation.domain.type.SteerChild;
import com.bifos.assistant.chat.conversation.domain.type.SteerState;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.TurnHandle;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.turn.application.dto.SteerSettlement;
import com.bifos.assistant.chat.turn.application.dto.SteerView;
import com.bifos.assistant.chat.turn.application.port.TurnSteerLedger;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
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
public class SteerLedger implements TurnSteerLedger {

    private final PendingMessageStore pendingMessages;
    private final ChatMessageRepository messages;
    private final ConversationRepository conversations;
    private final TurnCancellation turns;
    private final ConversationEventHub hub;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 정리하다 실패한 실행과 그 끝난 방식이다. 대화 번호마다 둔다. 다음 {@code tryNext} 가 그 실행만 같은 방식으로 다시 정리한다.
     *
     * <p>「전달됨」 말풍선만 보고 다시 정리하면 완료로 정리해 「전달됨」 으로 남긴 말풍선까지 되돌려, 이미 따른 지시가 다시 실행될 수 있다.
     * 메모리에만 둔다. 서버가 다시 뜨면 잊고, 그 말풍선은 「전달됨」 으로 남는다.
     */
    private final Map<Long, Map<Long, SteerSettlement>> unsettled = new ConcurrentHashMap<>();

    /** 끼우기 결과를 저장하지 못해 「끼우는 중」 글이 남았을 수 있는 대화다. 다음 {@code tryNext} 가 받았는지 모르는 글로 정리한다. */
    private final Set<Long> staleSteering = ConcurrentHashMap.newKeySet();


    /**
     * 끝난 turn 의 「전달됨」 말풍선을 끝난 방식대로 정리한다(설계의 「turn 이 끝날 때」).
     *
     * <p>이 프로세스에 그 실행의 turn 표시가 있으면 그 steer 잠금 안에서 「정리됨」 을 먼저 세운다. 도는 steer 요청이 먼저 끝나고, 그 뒤 온 요청은 거절로
     * 처리된다. 말풍선 상태 변경, 대기 줄 앞에 넣기, 멈춤은 한 트랜잭션이다. 실패하면 예외를 올리지 않고 「정리 못 함」 에 적는다.
     */
    @Override
    public void settle(Long rootExecutionId, Long conversationId, SteerSettlement settlement) {
        if (rootExecutionId == null || conversationId == null) {
            return;
        }
        TurnHandle handle = turns.find(rootExecutionId).orElse(null);
        if (handle == null) {
            settleRows(rootExecutionId, conversationId, settlement);
            return;
        }
        synchronized (handle.steerLock()) {
            handle.markSteerSettled();
            settleRows(rootExecutionId, conversationId, settlement);
        }
    }


    private void settleRows(Long rootExecutionId, Long conversationId, SteerSettlement settlement) {
        try {
            List<ChatMessage> changed = transactions.execute(status -> {
                List<ChatMessage> delivered = messages.findBySteerExecutionIdAndSteerStateOrderByIdAsc(rootExecutionId, SteerState.DELIVERED);
                if (delivered.isEmpty()
                    || settlement.kind() == SteerSettlement.Kind.COMPLETED && settlement.pendingSteer() == null) {
                    return List.<ChatMessage>of();
                }
                boolean completed = settlement.kind() == SteerSettlement.Kind.COMPLETED;
                List<ChatMessage> chosen = completed ? returned(delivered, settlement.pendingSteer()) : delivered;
                SteerState next = completed ? SteerState.CARRIED_OVER : SteerState.UNCONFIRMED;
                chosen.forEach(message -> message.changeSteerState(next));
                List<PendingMessages.Carried> carried = chosen.stream()
                    .map(message -> new PendingMessages.Carried(message.id(), message.content()))
                    .toList();
                pendingMessages.change(conversationId, queue -> {
                    PendingMessages prepended = queue.prependCarried(carried, clock.instant());
                    return completed ? prepended : prepended.withHeld(true);
                });
                return chosen;
            });
            announceRows(conversationId, changed);
        } catch (RuntimeException ex) {
            log.warn("steer 말풍선을 정리하지 못했다 conversationId={} executionId={}", conversationId, rootExecutionId, ex);
            unsettled.computeIfAbsent(conversationId, key -> new ConcurrentHashMap<>()).put(rootExecutionId, settlement);
        }
    }


    /**
     * 돌아온 글과 맞는 말풍선이다. Hermes 는 넣지 못한 글을 받은 순서대로 줄바꿈으로 이어 돌려준다. 앞 글은 이미 들어갔을 수 있어
     * 뒤쪽 묶음 가운데 이은 글이 같은 가장 긴 것을 고른다. 맞는 묶음이 없으면 글을 잃지 않게 모두 고른다(스펙 DECISION18).
     */
    static List<ChatMessage> returned(List<ChatMessage> delivered, String pendingSteer) {
        String target = pendingSteer.strip();
        for (int from = 0; from < delivered.size(); from++) {
            List<ChatMessage> tail = delivered.subList(from, delivered.size());
            String joined = tail.stream().map(ChatMessage::content).collect(Collectors.joining("\n")).strip();
            if (joined.equals(target)) {
                return List.copyOf(tail);
            }
        }
        return delivered;
    }


    @Override
    public void carryForward(Long conversationId, List<Long> messageIds) {
        messages.findAllById(messageIds).forEach(message -> message.changeSteerState(SteerState.CARRIED_OVER));
    }


    @Override
    public void announce(Conversation conversation, List<Long> messageIds) {
        messages.findAllById(messageIds).forEach(message -> publishSteer(conversation, message));
    }


    /** 정리하다 실패한 실행이 있으면 그 실행만 처음 끝난 방식대로 다시 정리한다. {@code NextTurnDispatcher.tryNext} 가 맨 앞에서 부른다. */
    public void resettleIfMarked(Long conversationId) {
        Map<Long, SteerSettlement> failed = unsettled.remove(conversationId);
        if (failed != null) {
            failed.forEach((executionId, settlement) -> settle(executionId, conversationId, settlement));
        }
        if (staleSteering.remove(conversationId)) {
            try {
                resolveStaleSteering(conversationId);
            } catch (RuntimeException ex) {
                staleSteering.add(conversationId);
                throw ex;
            }
        }
    }


    /** 끼우기 결과를 저장하지 못한 대화를 적어 둔다. 다음 {@code tryNext} 가 남은 「끼우는 중」 글을 정리한다. */
    public void markStaleSteering(Long conversationId) {
        staleSteering.add(conversationId);
    }


    /** 서버가 끼우기 답을 받기 전에 멈춰 남은 「끼우는 중」 글을 받았는지 모르는 글로 정리한다. */
    public void resolveStaleSteering(Long conversationId) {
        Conversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null) {
            return;
        }
        pendingMessages.read(conversationId).items().stream()
            .filter(PendingMessage::steering)
            .forEach(item -> unknown(conversation, item.id(), item.content(), conversation.userId(), null));
    }


    /**
     * 기동할 때 남은 「끼우는 중」 글을 정리한다. 재시작 정리({@code RestartReconciler}, {@code @Order(0)})와 다음 turn 깨우기보다 먼저
     * 돈다. 재시작 정리 설정과 상관없이 돈다. 한 대화가 실패해도 다음 대화를 이어 간다.
     *
     * <p>「전달됨」 말풍선은 훑지 않는다. 도는 중에 서버가 멈춘 turn 은 재시작 정리가 줄을 적으며 정리하고, 끝난 turn 의 「전달됨」 은 완료로
     * 정리해 남긴 것이라 되돌리면 이미 따른 지시가 다시 실행될 수 있다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(-1)
    public void onStartup() {
        for (Long conversationId : conversations.findIdsWithPendingMessages()) {
            try {
                if (pendingMessages.read(conversationId).blockedBySteering()) {
                    resolveStaleSteering(conversationId);
                }
            } catch (RuntimeException ex) {
                log.warn("남은 끼우는 중 글을 정리하지 못했다 conversationId={}", conversationId, ex);
            }
        }
    }


    private void announceRows(Long conversationId, List<ChatMessage> changed) {
        if (changed == null || changed.isEmpty()) {
            return;
        }
        conversations.findById(conversationId).ifPresent(conversation -> {
            changed.forEach(message -> publishSteer(conversation, message));
            hub.publish(conversationId, ChatEvent.pending(conversation.publicId()));
        });
    }


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
