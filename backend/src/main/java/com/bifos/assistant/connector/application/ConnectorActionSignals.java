package com.bifos.assistant.connector.application;

import static com.bifos.assistant.connector.application.ConnectorActionService.APPROVAL_EXPIRED_TITLE;

import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 승인 줄의 사건과 만료 알림을 기존 커밋 순서로 전한다. */
@Slf4j
class ConnectorActionSignals {
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private final ConversationNotices conversations;

    ConnectorActionSignals(
            ApplicationEventPublisher events, NotificationService notifications, ConversationNotices conversations) {
        this.events = events;
        this.notifications = notifications;
        this.conversations = conversations;
    }

    /** 거절한 줄의 사건을 트랜잭션이 커밋한 뒤에 낸다. 트랜잭션 밖이면 바로 낸다. */
    void publishAfterCommit(List<ConnectorAction> rejected) {
        if (rejected.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rejected.forEach(this::publish);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rejected.forEach(ConnectorActionSignals.this::publish);
            }
        });
    }

    /**
     * 만료한 승인 줄의 주인에게 알린다. 그 줄을 만료로 바꾼 트랜잭션 안에서 부른다.
     *
     * <p>대화 없이 돈 실행의 줄과, 대화가 지워졌거나 없는 줄은 눌러도 갈 곳이 없어 남기지 않는다. 도구 제목은 승인 카드와
     * 같은 규칙이다.
     *
     * @param manifest 그 줄의 커넥터 manifest. 읽지 못했으면 빈 값
     */
    void notifyExpired(ConnectorAction action, Optional<ConnectorManifest> manifest) {
        if (action.conversationId() == null) {
            return;
        }
        String toolTitle = ConnectorActionView.titleOf(
                manifest.flatMap(found -> ConnectorToolPolicies.find(found, action.toolName())));
        conversations
                .publicIdOf(action.conversationId())
                .ifPresent(conversationId -> notifications.notify(
                        action.userId(),
                        NotificationKind.APPROVAL_EXPIRED,
                        APPROVAL_EXPIRED_TITLE,
                        "「" + toolTitle + "」",
                        new NotificationTarget(NotificationTargetType.CONVERSATION, conversationId)));
    }

    /**
     * 대화 없는 실행이 만든 줄은 전할 곳이 없어 내지 않는다. 줄을 커밋한 뒤에만 부른다.
     *
     * <p>듣는 쪽의 예외를 밖으로 내지 않는다. 줄은 이미 커밋됐고, 예외가 나가면 실행한 승인이 실패한 것처럼 보인다.
     */
    void publish(ConnectorAction action) {
        if (action.conversationId() == null) {
            return;
        }
        try {
            events.publishEvent(new ConnectorActionChanged(action.conversationId(), action.publicId()));
        } catch (RuntimeException ex) {
            log.warn("승인 줄이 바뀐 것을 알리지 못했다 actionId={}", action.publicId(), ex);
        }
    }
}
