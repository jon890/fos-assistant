package com.bifos.assistant.connector.handoff.application;

import com.bifos.assistant.browser.application.BrowserHumanHold;
import com.bifos.assistant.browser.application.BrowserLeftTabs;
import com.bifos.assistant.chat.turn.application.ConversationNotices;
import com.bifos.assistant.connector.action.application.ConnectorBrowserKeptTabs;
import com.bifos.assistant.connector.action.application.dto.ConnectorActionChanged;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ConnectorActionKey;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
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
import com.bifos.assistant.orchestration.application.dto.PluginCallEnded;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * 플러그인이 사람에게 화면을 넘긴 호출을 판정 줄에 적고, 알림과 대화의 카드를 내고, 같은 플러그인의 다음 호출이 끝나면 닫는다.
 *
 * <p>흐름은 {@code docs/adr/ADR-20261111-connector-handoff.md} 의 「결정」 이, 닫히는 때는
 * {@code docs/features/mobile-default-chat/design.md} 의 「넘기기의 상태」 가 갖는다. 사람이 보낸 turn 은 닫지 않는다. 카드의 단추는
 * {@link HandoffButtons} 가 닫는다. 서비스 이름을 모른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorHandoffs {

    private final ConnectorHandoffRepository handoffs;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final ConnectorCatalogCache catalog;
    private final BrowserHumanHold holds;
    private final BrowserLeftTabs leftTabs;
    private final ConnectorBrowserKeptTabs keptTabs;
    private final ApplicationEventPublisher events;
    private final Clock clock;


    /**
     * 같은 호출의 판정 줄에 넘기기를 적는다. 처음 적은 때만 알림 한 건을 남기고, 같은 대화에서 같은 플러그인이 앞서 낸 열린 넘기기를 닫는다
     * (spec DECISION23).
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
        handoffs.findOpenOfConnector(action.userId(), action.conversationId(), action.connectorId()).stream()
            .filter(earlier -> !earlier.id().equals(action.id()) && plugin(earlier))
            .forEach(earlier -> close(earlier, true));
        conversations.publicIdOf(action.conversationId())
            .filter(conversation -> !watchingHold(action.userId(), request.need(), conversation))
            .ifPresent(conversation -> notifications.notify(
                action.userId(),
                NotificationKind.HANDOFF_REQUESTED,
                request.need().title(),
                "「" + titleOf(action.connectorId()) + "」",
                new NotificationTarget(NotificationTargetType.CONVERSATION, conversation),
                action.publicId()));
        afterCommit(action);
    }


    /**
     * 사람이 그 대화의 패널에서 브라우저를 넘겨받아 생긴 넘기기인가. 보고 있는 대화의 카드라 알림은 소음이다(spec DECISION18). 카드는 그대로
     * 낸다. 다른 대화나 「내 브라우저」 에서 넘겨받았으면 알린다.
     */
    private boolean watchingHold(Long userId, HandoffNeed need, UUID conversation) {
        return need == HandoffNeed.HUMAN_BROWSER && holds.heldFrom(userId).filter(conversation.toString()::equals).isPresent();
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
     * 도구 기록이 알린 호출이 플러그인 호출이고 넘기기 없이 끝났으면, 같은 사용자, 대화, 플러그인의 앞선 열린 넘기기를 닫는다(spec
     * DECISION13, DECISION23). 실패한 호출, 넘기기를 낸 호출, 판정 줄이 없는 호출(플러그인이 아닌 도구)은 닫지 않는다.
     *
     * <p>Hermes 는 도구 기록 hook 을 넘기기 알림보다 먼저 부르므로 같은 호출의 기록이 먼저 올 수 있다. 그때는 넘기기가 아직 없어 앞선 것을 닫고, 뒤이어
     * 온 넘기기가 새 카드를 연다. 어느 순서든 결과가 같다. 트랜잭션 안에서 났으면 커밋한 뒤, 밖에서 났으면 바로 새 트랜잭션에서 돈다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPluginCallEnded(PluginCallEnded event) {
        if (event.failed()) {
            return;
        }
        String key;
        try {
            key = ConnectorActionKey.of(event.profileName(), event.rootSessionId(), event.sessionId(), event.toolCallId()).value();
        } catch (IllegalArgumentException ex) {
            return;
        }
        handoffs.findByDedupeKey(key)
            .filter(action -> action.passed() && action.conversationId() != null && action.handoff() == null)
            .ifPresent(succeeded -> handoffs.findOpenOfConnector(
                succeeded.userId(), succeeded.conversationId(), succeeded.connectorId()).stream()
                .filter(earlier -> plugin(earlier) && earlier.handoff().at().isBefore(succeeded.createdAt()))
                .forEach(earlier -> close(earlier, true)));
    }


    /**
     * 플러그인 호출의 성공이 닫을 수 있는 넘기기인가. 승인한 주문서 채우기가 사람 화면에서 기다리는 줄({@code WAITING})은 「다 했어요」 로만 닫는다. 그
     * 넘기기가 닫혀야 같은 승인으로 이어 가므로, 같은 플러그인의 다른 호출이 닫으면 탭과 연결 확인 없이 이어 간다.
     */
    private static boolean plugin(ConnectorAction action) {
        return action.status() != ActionStatus.WAITING;
    }


    /**
     * 열린 넘기기 하나를 닫고 그 알림을 읽음으로 바꾼 뒤 대화에 알린다. 부르는 쪽의 트랜잭션 안에서 돈다.
     *
     * @param releaseTab 커밋한 뒤 남긴 탭도 닫는가. 플러그인의 다음 호출이 닫을 때 참이다. 그 호출이 시작할 때 탭을 닫으려 했지만 카드가 열려 있어
     * 중계가 거절했다. 단추가 닫을 때는 거짓이다. 이어 가는 다음 호출이 탭을 닫는다
     * @return 이 호출이 닫았으면 참. 이미 닫혀 있었으면 거짓이고 아무것도 하지 않는다
     */
    @Transactional
    public boolean close(ConnectorAction action, boolean releaseTab) {
        if (handoffs.close(List.of(action.id()), clock.instant()) == 0) {
            return false;
        }
        notifications.resolve(action.userId(), NotificationKind.HANDOFF_REQUESTED, action.publicId());
        afterCommit(action);
        if (releaseTab && action.handoff().tab() != null) {
            String tab = action.handoff().tab();
            runAfterCommit(() -> {
                if (!keptTabs.kept(action.userId(), tab, action.handoff().at())) {
                    leftTabs.close(action.userId(), tab, action.handoff().at());
                }
            });
        }
        return true;
    }


    /** 카드를 다시 읽게 대화에 알린다. 승인 줄이 바뀐 것과 같은 사건이다. 예외는 밖으로 내지 않는다. */
    private void afterCommit(ConnectorAction action) {
        if (action.conversationId() == null) {
            return;
        }
        ConnectorActionChanged changed = new ConnectorActionChanged(action.conversationId(), action.publicId());
        runAfterCommit(() -> events.publishEvent(changed));
    }


    /** 트랜잭션이 있으면 커밋한 뒤, 없으면 바로 돈다. 예외는 로그만 남긴다. */
    private static void runAfterCommit(Runnable work) {
        Runnable guarded = () -> {
            try {
                work.run();
            } catch (RuntimeException ex) {
                log.warn("화면 넘기기를 닫은 뒤의 일을 하지 못했다 kind={}", ex.getClass().getSimpleName());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            guarded.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                guarded.run();
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
