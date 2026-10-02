package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.chat.application.DelegationWakeService;
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.application.model.ConnectorActionResult;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
    private final Clock clock;

    @Autowired
    public ConnectorActionListener(
            ConnectorActionService actions,
            ConversationEventHub hub,
            ConversationNotices notices,
            DelegationWakeService wake) {
        this(actions, hub, notices, wake, Clock.systemUTC());
    }

    ConnectorActionListener(
            ConnectorActionService actions,
            ConversationEventHub hub,
            ConversationNotices notices,
            DelegationWakeService wake,
            Clock clock) {
        this.actions = actions;
        this.hub = hub;
        this.notices = notices;
        this.wake = wake;
        this.clock = clock;
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
            for (ConnectorActionResult closure : actions.undeliveredClosures(conversationId)) {
                // 전했다고 먼저 적는다. 두 사건이 겹쳐도 같은 알림 줄이 두 번 남지 않는다.
                if (actions.claimDelivery(closure.actionId(), Instant.now(clock))) {
                    notices.post(conversationId, closureNotice(closure));
                }
            }
            wake.tryWake(conversationId);
        } catch (RuntimeException ex) {
            log.warn(
                    "승인 줄이 바뀐 것을 대화에 전하지 못했다 actionId={} kind={}",
                    event.actionId(),
                    ex.getClass().getSimpleName());
        }
    }

    /** 사용자가 거절한 것과 시스템이 실행하지 않고 끝낸 것을 다른 글로 알린다. */
    private static String closureNotice(ConnectorActionResult closure) {
        if (closure.status() == ActionStatus.EXPIRED) {
            return closure.title() + " 요청이 승인 없이 만료됐어요";
        }
        if (ConnectorAction.CONNECTION_CHANGED.equals(closure.errorCode())) {
            return "연결이 바뀌어 " + closure.title() + " 요청을 취소했어요";
        }
        if (ConnectorAction.NOT_EXECUTABLE.equals(closure.errorCode())) {
            return closure.title() + " 요청을 지금은 실행할 수 없어 취소했어요. 연결 화면에서 연결을 확인해 주세요";
        }
        return closure.title() + " 요청을 거절했어요";
    }
}
