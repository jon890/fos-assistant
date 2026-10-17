package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
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
    /** 만료한 승인 줄을 알리는 알림의 제목이다. 본문은 도구 제목이다. */
    static final String APPROVAL_EXPIRED_TITLE = "승인 요청이 만료됐어요";
    /** 새 승인 줄을 알리는 알림의 제목이다. 본문은 도구 제목이다. */
    static final String APPROVAL_REQUESTED_TITLE = "승인을 기다리는 요청이 있어요";

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


    /** 승인 줄의 주인에게 새 요청을 알린다. 대화가 지워졌거나 없으면 눌러도 갈 곳이 없어 남기지 않는다. 그 줄을 저장하는 트랜잭션 안에서 부른다. */
    void notifyRequested(ConnectorAction action, String toolTitle) {
        conversations
            .publicIdOf(action.conversationId())
            .ifPresent(conversationId -> notifications.notify(
                action.userId(),
                NotificationKind.APPROVAL_REQUESTED,
                APPROVAL_REQUESTED_TITLE,
                "「" + toolTitle + "」",
                new NotificationTarget(NotificationTargetType.CONVERSATION, conversationId),
                action.publicId()));
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
     * 끝난 승인 줄의 「승인을 기다리는 요청」 알림을 읽음으로 표시한다. 그 줄이 만든 알림만 바꾸므로 같은 대화의 다른 줄이나
     * 그 뒤에 생긴 줄의 알림은 건드리지 않는다. 대화가 지워졌어도 바꾼다. 트랜잭션 안에서 부른다.
     */
    void resolveRequested(ConnectorAction action) {
        notifications.resolve(action.userId(), NotificationKind.APPROVAL_REQUESTED, action.publicId());
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
