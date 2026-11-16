package com.bifos.assistant.connector.approval.application;

import static com.bifos.assistant.connector.approval.application.ConnectorFinancialProducer.require;

import com.bifos.assistant.chat.delivery.application.dto.AutoTurnResult;
import com.bifos.assistant.chat.turn.application.ConversationNotices;
import com.bifos.assistant.connector.action.application.ConnectorActionDetails;
import com.bifos.assistant.connector.action.application.ConnectorActionSignals;
import com.bifos.assistant.connector.action.application.ConnectorApprovalBrowser;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionBodies;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorManifests;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionCurrent;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionGuard;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.financial.application.ConnectorFinancialConflicts;
import com.bifos.assistant.connector.financial.application.ConnectorFinancialGate;
import com.bifos.assistant.connector.financial.application.PurchaseLimits;
import com.bifos.assistant.connector.financial.application.dto.ConnectorExecutionCatalog;
import com.bifos.assistant.connector.financial.application.dto.ConnectorExecutionReadContext;
import com.bifos.assistant.connector.financial.domain.PurchaseLimitExceeded;
import com.bifos.assistant.hermes.application.dto.ConnectorApprovedExecution;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.mcp.application.port.ApprovalResumes;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사람 화면에서 멈춘 승인한 주문서 채우기를 같은 승인으로 이어 간다(docs/features/browser-cowork/spec.md 의 REQ8, REQ9, 설계의
 * 「PR4 이어 가기」). 에이전트의 MCP 도구가 부른다.
 *
 * <p>그 대화의 {@code WAITING} 줄 하나만 고른다. 사람이 그 뒤 이 대화에 메시지를 보내 넘기기 카드가 닫혀 있어야 하고, 수명과 같은 브라우저와
 * 그 순간의 구매 상한을 다시 본다. 이어 가는 전이({@code WAITING} 에서 {@code EXECUTING})를 행 잠금 아래에서 한 번만 하므로 멈출 때마다 한
 * 번이다. 실행은 처음 승인과 같은 {@link ConnectorFinancialApproval#run} 이고 ticket 을 새로 낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ConnectorApprovalResume implements ApprovalResumes {
    static final String NONE = "이 대화에 이어 갈 승인이 없다. 승인이 이미 끝났거나 이어 갈 수 있는 시간이 지났다."
        + " 사용자가 여전히 원하면 처음처럼 준비해 새 승인을 받는다.";
    static final String NOT_YET = "사용자가 아직 「내 브라우저」에서 마쳤다고 하지 않았다. 사용자가 마치고 이 대화에 다음 메시지를 보낸 뒤 다시 부른다.";
    static final String HELD = "사용자가 「내 브라우저」를 직접 쓰고 있어 이어 가지 않았다. 사용자가 「돌려주기」를 누른 뒤 다시 부른다.";
    static final String ENDED = "이어 가지 못하고 승인을 끝냈다. 다시 하려면 처음처럼 준비해 새 승인을 받는다.";

    private final ConnectorFinancialApproval approval;
    private final ConnectorExecutionCurrent current;
    private final ConnectorExecutionGuard guard;
    private final ConnectorFinancialConflicts conflicts;
    private final ConnectorFinancialGate gate;
    private final PurchaseLimits purchases;
    private final HermesConnectorClient hermes;
    private final ConnectorApprovalBrowser browsers;
    private final ConnectorActionResultSource results;
    private final AppUserRepository users;
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final ConnectorActionBodies bodies;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final ApplicationEventPublisher events;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;


    @Override
    public String resume(CurrentUser user, Long conversationId) {
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        Optional<ConnectorAction> found = conversationId == null ? Optional.empty()
            : actions.findFirstByUserIdAndConversationIdAndStatusOrderByIdDesc(user.id(), conversationId, ActionStatus.WAITING);
        if (found.isEmpty()) {
            return NONE;
        }
        ConnectorAction waiting = found.get();
        UUID actionId = waiting.publicId();
        if (!waiting.expiresAt().isAfter(clock.instant())
            || !browsers.runningSince(user.id(), waiting.waiting().since())) {
            // 수명이 다했거나 남긴 탭이 있던 브라우저가 꺼졌다. 표식과 승인을 함께 끝낸다(spec REQ9).
            return ended(user, conversationId, actionId);
        }
        if (waiting.handoff() == null || waiting.handoff().closedAt() == null) {
            return NOT_YET;
        }
        if (browsers.held(user.id())) {
            return HELD;
        }
        Prepared prepared;
        try {
            var context = current.readContext(actionId);
            var catalog = current.readCatalog(context);
            var manifest = ConnectorManifests.find(hermes, context.connectorId()).orElseThrow(ConnectorFinancialProducer::rejected);
            require(catalog.guard().equals(guard.read(manifest)));
            ConnectorFinancialProducer.verified(hermes, manifest, context.profile(), context.bindingId(), context.connectionId(),
                context.connectionUpdatedAt());
            prepared = new Prepared(context, catalog, manifest);
        } catch (RuntimeException ex) {
            // 연결이나 선언이 바뀌어 같은 승인으로 실행할 수 없다. 기다림을 끝내 새로 준비하게 한다.
            log.warn("이어 가기 맥락 확인 실패 actionId={} kind={}", actionId, ex.getClass().getSimpleName());
            return ended(user, conversationId, actionId);
        }
        Optional<Started> started;
        try {
            started = new TransactionTemplate(transactionManager).execute(status -> begin(user, conversationId, actionId, prepared));
        } catch (RuntimeException ex) {
            // 잠근 줄의 snapshot 이나 관문이 같은 승인을 다시 실행하지 못하게 한다. 롤백된 줄을 따로 끝낸다.
            log.warn("이어 가기 확인 실패 actionId={} kind={}", actionId, ex.getClass().getSimpleName());
            return ended(user, conversationId, actionId);
        }
        if (started.isEmpty()) {
            return render(user, conversationId, actionId, NONE);
        }
        if (started.get().snapshot() == null) {
            return render(user, conversationId, actionId, ENDED);
        }
        approval.run(user, actionId, prepared.context(), prepared.manifest(), started.get().snapshot(), started.get().resume());
        return render(user, conversationId, actionId, NONE);
    }


    /**
     * 잠근 줄을 다시 보고 이어 간다. 줄이 바뀌었으면 빈 값, 그 순간의 상한에 걸려 끝냈으면 snapshot 이 없는 값이다(spec DECISION19).
     * 자기 줄을 뺀 승인 날짜의 합계로 본다. 뒤의 claim 이 같은 날짜로 다시 보므로 판정이 어긋나지 않는다.
     */
    private Optional<Started> begin(CurrentUser user, Long conversationId, UUID actionId, Prepared prepared) {
        users.findByIdForUpdate(user.id()).orElseThrow(ConnectorActionDetails::notFound);
        var action = actions.findByPublicIdForUpdate(actionId).orElseThrow(ConnectorActionDetails::notFound);
        Instant now = clock.instant();
        if (action.status() != ActionStatus.WAITING || !Objects.equals(action.userId(), user.id())
            || !Objects.equals(action.conversationId(), conversationId) || !action.expiresAt().isAfter(now)
            || action.handoff() == null || action.handoff().closedAt() == null) {
            return Optional.empty();
        }
        var row = contents.findByActionIdForUpdate(action.id()).orElseThrow(ConnectorFinancialProducer::rejected);
        ConnectorExecutionSnapshot checked = current.validateLocked(prepared.context(), prepared.catalog(), action, row);
        conflicts.requireAvailable(row.requestKey(), action.id());
        gate.requireOpen();
        long expected = checked.expectedTotalKrw().orElseThrow(ConnectorFinancialProducer::rejected);
        try {
            purchases.requireWithin(action.userId(), action.id(), expected, action.decidedAt());
        } catch (PurchaseLimitExceeded ex) {
            ConnectorCheckoutOutcome.limited(action, ex, expected, bodies, now);
            settled(action, now);
            return Optional.of(new Started(null, null));
        }
        var resume = new ConnectorApprovedExecution.Resume(action.handoff().tab(), action.waiting().step());
        action.resume(now);
        return Optional.of(new Started(checked, resume));
    }


    /**
     * 수명이 다했거나 남긴 탭이 있던 브라우저가 꺼진 사람 기다림을 끝낸다(spec REQ9). 일정이 1분마다 부른다. 끝낸 줄은 하루 합계에서
     * 빠진다. 모델은 이미 멈춤을 전해 받았으므로 다시 깨우지 않고, 다음 이어 가기가 끝났다는 결과를 받는다.
     *
     * @return 끝낸 줄 수
     */
    int endStale(Instant now) {
        int ended = 0;
        for (ConnectorAction candidate : actions.findByStatus(ActionStatus.WAITING)) {
            try {
                boolean stale = !candidate.expiresAt().isAfter(now)
                    || !browsers.runningSince(candidate.userId(), candidate.waiting().since());
                if (stale && endLocked(candidate.userId(), candidate.publicId(), now)) {
                    ended++;
                }
            } catch (RuntimeException ex) {
                log.warn("사람 기다림을 끝내지 못했다 actionId={} kind={}", candidate.publicId(), ex.getClass().getSimpleName());
            }
        }
        return ended;
    }


    /** 그 줄이 아직 기다림이면 끝내고 모델에게 줄 글을 돌려준다. */
    private String ended(CurrentUser user, Long conversationId, UUID actionId) {
        endLocked(user.id(), actionId, clock.instant());
        return render(user, conversationId, actionId, ENDED);
    }


    /** 사용자와 줄을 잠그고 아직 기다림이면 끝낸다. 그 사이 이어 갔거나 끝났으면 건드리지 않는다. */
    private boolean endLocked(Long userId, UUID actionId, Instant now) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            users.findByIdForUpdate(userId).orElseThrow(ConnectorActionDetails::notFound);
            return actions.findByPublicIdForUpdate(actionId)
                .filter(action -> action.status() == ActionStatus.WAITING)
                .map(action -> {
                    ConnectorCheckoutOutcome.endWaiting(action, bodies, now);
                    settled(action, now);
                    return true;
                })
                .orElse(false);
        }));
    }


    /** 이어 가기에서 끝낸 줄은 이 도구의 결과로 모델에게 가므로 자동 turn 으로 다시 전하지 않는다. 카드는 사건으로 다시 읽는다. */
    private void settled(ConnectorAction action, Instant now) {
        if (action.resultDeliveredAt() == null) {
            action.markDelivered(now);
        }
        new ConnectorActionSignals(events, notifications, conversations).publishAfterCommit(List.of(action));
    }


    /** 그 줄의 결과를 자동 turn 과 같은 글로 준다. 끝나지 않은 줄이면 {@code fallback} 이다. 끝낸 줄이면 안내를 앞에 둔다. */
    private String render(CurrentUser user, Long conversationId, UUID actionId, String fallback) {
        List<AutoTurnResult> rendered = results.resultsFor(conversationId, user.id(), List.of(actionId.toString()));
        if (rendered.isEmpty()) {
            return fallback;
        }
        String input = rendered.get(0).input();
        return Objects.equals(fallback, ENDED) ? ENDED + "\n" + input : input;
    }


    private record Prepared(ConnectorExecutionReadContext context, ConnectorExecutionCatalog catalog, ConnectorManifest manifest) {
    }

    private record Started(ConnectorExecutionSnapshot snapshot, ConnectorApprovedExecution.Resume resume) {
    }
}
