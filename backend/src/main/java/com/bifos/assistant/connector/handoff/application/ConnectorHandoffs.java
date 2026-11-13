package com.bifos.assistant.connector.handoff.application;

import com.bifos.assistant.chat.turn.application.ConversationNotices;
import com.bifos.assistant.chat.turn.application.dto.ConversationTurnStarted;
import com.bifos.assistant.connector.action.application.dto.ConnectorActionChanged;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ConnectorActionKey;
import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import com.bifos.assistant.connector.approval.application.dto.PaymentHandoffResult;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.handoff.application.dto.ConnectorHandoffRequest;
import com.bifos.assistant.connector.handoff.application.dto.HandoffView;
import com.bifos.assistant.connector.handoff.infra.ConnectorHandoffRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 플러그인이 사람에게 화면을 넘긴 호출을 판정 줄에 적고, 알림과 대화의 카드를 내고, 사람이 다음 turn 을 시작하면 닫는다.
 *
 * <p>흐름은 {@code docs/adr/ADR-20261111-connector-handoff.md} 의 「결정」 이 갖는다. 서비스 이름을 모른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorHandoffs {

    private final ConnectorHandoffRepository handoffs;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final ConnectorCatalogCache catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;


    /**
     * 같은 호출의 판정 줄에 넘기기를 적는다. 처음 적은 때만 알림 한 건을 남긴다.
     *
     * <p>판정 줄이 없거나, hook 에 통과로 답한 줄이 아니거나, 등록 이름이 다르면 거절한다. 판정을 지난 호출의 결과만
     * 넘기기가 될 수 있다.
     *
     * @param profileName 요청을 인증한 profile
     */
    @Transactional
    public void record(String profileName, ConnectorHandoffRequest request) {
        String key = ConnectorActionKey.of(
            profileName, request.rootSessionId(), request.sessionId(), request.toolCallId()).value();
        ConnectorAction action = handoffs.findByDedupeKey(key)
            .orElseThrow(() -> ConnectorHandoffRequest.reject("같은 호출의 판정 줄이 없다"));
        if (!action.passed() || !action.hermesTool().equals(request.hermesTool())) {
            throw ConnectorHandoffRequest.reject("통과한 같은 도구의 판정 줄이 아니다");
        }
        if (handoffs.record(action.id(), request.need(), request.tab(), clock.instant()) == 0) {
            return;
        }
        if (action.conversationId() == null) {
            // 대화 밖에서 돈 실행이다. 넘기기는 적되 눌러 갈 곳이 없어 알리지 않는다.
            return;
        }
        conversations.publicIdOf(action.conversationId())
            .ifPresent(conversation -> notifications.notify(
                action.userId(),
                NotificationKind.HANDOFF_REQUESTED,
                request.need().title(),
                "「" + titleOf(action.connectorId()) + "」",
                new NotificationTarget(NotificationTargetType.CONVERSATION, conversation),
                action.publicId()));
        afterCommit(action);
    }


    /** 주인의 대화에서 열린 넘기기다. 대화가 주인의 것인지는 부르는 쪽이 이미 확인했다. */
    @Transactional(readOnly = true)
    public List<HandoffView> open(CurrentUser user, Long conversationId) {
        return handoffs.findOpen(conversationId, user.id()).stream()
            .map(this::view)
            .toList();
    }


    /** 결제 넘기기는 금액을 새 칸 없이 그 승인 줄의 결과 글에서 읽어 붙인다(spec DECISION25). 읽지 못하면 금액 없이 낸다. */
    private HandoffView view(ConnectorAction action) {
        var amounts = action.handoff().need() == HandoffNeed.PAYMENT
            ? PaymentHandoffResult.fromStored(action.resultText()) : Optional.<PaymentHandoffResult>empty();
        return new HandoffView(
            action.publicId(),
            action.connectorId(),
            titleOf(action.connectorId()),
            action.handoff().need(),
            action.handoff().tab(),
            action.handoff().at(),
            amounts.map(PaymentHandoffResult::finalAmountKrw).orElse(null),
            amounts.map(PaymentHandoffResult::expectedTotalKrw).orElse(null));
    }


    /**
     * 그 대화에서 사람이 보낸 turn 이 시작되면 열린 넘기기를 닫고 그 알림을 읽음으로 바꾼다. 결과 전달 같은 자동 turn 은
     * 사용자가 보기 전이라 닫지 않는다. 트랜잭션 안에서 났으면 커밋한 뒤, 밖에서 났으면 바로 새 트랜잭션에서 돈다. 예외는 사건을 낸 쪽이 받아 로그만 남기므로 닫지 못해도 turn 은 잇는다. 카드가 남을 뿐이다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTurnStarted(ConversationTurnStarted event) {
        if (!event.byUser()) {
            return;
        }
        List<ConnectorAction> open = handoffs.findOpenInConversation(event.conversationId());
        if (open.isEmpty()) {
            return;
        }
        handoffs.close(open.stream().map(ConnectorAction::id).toList(), clock.instant());
        open.forEach(action -> notifications.resolve(action.userId(), NotificationKind.HANDOFF_REQUESTED, action.publicId()));
        open.forEach(this::afterCommit);
    }


    /** 카드를 다시 읽게 대화에 알린다. 승인 줄이 바뀐 것과 같은 사건이다. 예외는 밖으로 내지 않는다. */
    private void afterCommit(ConnectorAction action) {
        if (action.conversationId() == null) {
            return;
        }
        ConnectorActionChanged changed = new ConnectorActionChanged(action.conversationId(), action.publicId());
        Runnable publish = () -> {
            try {
                events.publishEvent(changed);
            } catch (RuntimeException ex) {
                log.warn("화면 넘기기가 바뀐 것을 알리지 못했다 actionId={}", action.publicId(), ex);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }


    /** 카탈로그의 플러그인 이름이다. 읽지 못하면 플러그인 번호다. */
    private String titleOf(String connectorId) {
        try {
            return catalog.find(connectorId).map(ConnectorManifest::title).orElse(connectorId);
        } catch (RuntimeException ex) {
            return connectorId;
        }
    }
}
