package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.chat.application.DelegationWakeService;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.application.model.ConnectorActionResult;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승인 줄이 생기거나 바뀌면 그 요청이 나온 대화에 알린다(ADR-050).
 *
 * <p>화면에는 {@code approval} 사건을 내 승인 줄을 다시 읽게 한다. 거절과 만료는 알림 줄만 남긴다. 실행한 결과는
 * {@link ConnectorActionResultSource} 가 내고, 여기서는 그 대화를 깨우기만 한다. 방향은 {@code connector} 에서
 * {@code chat} 으로 하나다.
 *
 * <p>승인이나 정책 판정을 한 스레드에서 그대로 돈다. 줄은 이미 커밋됐으므로 여기서 난 예외를 밖으로 내지 않는다.
 */
@Slf4j
@Component
public class ConnectorActionListener {

    private final ConnectorActionService actions;
    private final ConversationEventHub hub;
    private final ConversationNotices notices;
    private final DelegationWakeService wake;
    private final TurnCancellation turns;
    private final TransactionTemplate ownTransaction;
    private final Clock clock = Clock.systemUTC();

    public ConnectorActionListener(
            ConnectorActionService actions,
            ConversationEventHub hub,
            ConversationNotices notices,
            DelegationWakeService wake,
            TurnCancellation turns,
            PlatformTransactionManager transactionManager) {
        this.actions = actions;
        this.hub = hub;
        this.notices = notices;
        this.wake = wake;
        this.turns = turns;
        // 사건은 연결 해제처럼 다른 트랜잭션이 커밋한 직후의 콜백에서도 온다. 그 자리에서는 끝난 트랜잭션이 아직
        // 묶여 있어, 거기에 참여하면 쓰기가 저장되지 않는다. 늘 새 트랜잭션에서 쓴다.
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 도는 turn 때문에 미룬 알림 줄을 그 turn 이 닫힐 때 남긴다.
        turns.addCloseListener(closed -> deliverClosures(closed.conversationId()));
    }

    @EventListener
    public void onChanged(ConnectorActionChanged event) {
        Long conversationId = event.conversationId();
        if (conversationId == null) {
            return;
        }
        try {
            notices.publicIdOf(conversationId)
                    .ifPresent(publicId -> hub.publish(conversationId, ChatEvent.approval(publicId, event.actionId())));
        } catch (RuntimeException ex) {
            log.warn(
                    "승인 줄이 바뀐 것을 화면에 알리지 못했다 actionId={} kind={}",
                    event.actionId(),
                    ex.getClass().getSimpleName());
        }
        deliverClosures(conversationId);
        try {
            wake.tryWake(conversationId);
        } catch (RuntimeException ex) {
            log.warn(
                    "승인 결과를 전할 대화를 깨우지 못했다 conversationId={} kind={}",
                    conversationId,
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * 실행하지 않고 끝난 줄마다 알림 줄을 남긴다. 전했다는 표시와 알림 줄을 한 트랜잭션에 넣는다.
     *
     * <p>그 대화의 turn 이 도는 동안에는 남기지 않는다. 답보다 먼저 알림 줄이 끼면 그 답이 질문이 아니라 알림 줄에
     * 이어진 것으로 읽힌다. turn 이 닫힐 때 다시 불린다. 표시를 먼저 적어, 두 사건이 겹쳐도 같은 알림 줄이 두 번
     * 남지 않는다.
     */
    private void deliverClosures(Long conversationId) {
        try {
            if (turns.markOf(conversationId).running()) {
                return;
            }
            ownTransaction.executeWithoutResult(status -> {
                for (ConnectorActionResult closure : actions.undeliveredClosures(conversationId)) {
                    if (actions.claimDelivery(closure.actionId(), Instant.now(clock))) {
                        notices.post(conversationId, closureNotice(closure));
                    }
                }
            });
        } catch (RuntimeException ex) {
            log.warn(
                    "끝난 승인 요청을 대화에 알리지 못했다 conversationId={} kind={}",
                    conversationId,
                    ex.getClass().getSimpleName());
        }
    }

    /** 사용자가 거절한 것과 시스템이 실행하지 않고 끝낸 것을 다른 글로 알린다. */
    private static String closureNotice(ConnectorActionResult closure) {
        String name = "「" + closure.title() + "」";
        if (closure.status() == ActionStatus.EXPIRED) {
            return name + " 요청이 승인 없이 만료됐어요";
        }
        if (ConnectorAction.CONNECTION_CHANGED.equals(closure.errorCode())) {
            return "연결이 바뀌어 " + name + " 요청을 취소했어요";
        }
        if (ConnectorAction.NOT_EXECUTABLE.equals(closure.errorCode())) {
            return name + " 요청을 지금은 실행할 수 없어 취소했어요. 연결 화면에서 연결을 확인해 주세요";
        }
        return name + " 요청을 거절했어요";
    }
}
