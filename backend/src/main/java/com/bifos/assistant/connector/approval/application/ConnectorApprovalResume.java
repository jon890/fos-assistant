package com.bifos.assistant.connector.approval.application;

import static com.bifos.assistant.connector.approval.application.ConnectorFinancialProducer.require;

import com.bifos.assistant.chat.delivery.application.dto.AutoTurnResult;
import com.bifos.assistant.chat.turn.application.ConversationNotices;
import com.bifos.assistant.connector.action.application.ConnectorActionDetails;
import com.bifos.assistant.connector.approval.application.dto.FinancialPrepareStage;
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
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.shared.util.ToolFailureText;
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
    // 이어 가지 못한 글은 승인이나 사람 손이 필요해 바로 알림으로 끝난다(docs/features/tool-fallback/spec.md 의 REQ2, REQ3). RUNNING 은 실패가 아니다.
    static final String NONE = ToolFailureText.askUser("이 대화에 이어 갈 승인이 없다. 승인이 이미 끝났거나 이어 갈 수 있는 시간이 지났다."
        + " 사용자가 여전히 원하면 처음처럼 준비해 새 승인을 받는다.").text();
    static final String NOT_YET = ToolFailureText.askUser("사용자가 아직 「내 브라우저」에서 마쳤다고 하지 않았다. 사용자가 마치고 이 대화에 다음 메시지를 보낸 뒤 다시 부른다.").text();
    static final String HELD = ToolFailureText.askUser("사용자가 「내 브라우저」를 직접 쓰고 있어 이어 가지 않았다. 사용자가 「돌려주기」를 누른 뒤 다시 부른다.").text();
    /** 끝낸 줄의 결과(ADR-092 의 복구 안내)를 뒤에 붙일 때 쓰는 앞글이다. 끝 줄은 그 결과가 갖는다. */
    static final String ENDED_LEAD = "이어 가지 못하고 승인을 끝냈다. 다시 하려면 처음처럼 준비해 새 승인을 받는다.";
    static final String ENDED = ToolFailureText.askUser(ENDED_LEAD).text();
    static final String RUNNING = "이미 이어 가는 중이다. 새로 준비하지 말고 그 결과를 기다린다.";
    static final String RETRY = ToolFailureText.askUser("지금은 플러그인 상태를 확인하지 못해 이어 가지 않았다. 승인은 그대로다. 사용자에게 잠시 뒤 다시 하겠다고 알리고 새로 준비하지 않는다.").text();
    static final String SENT = ToolFailureText.askUser("같은 승인으로 이어 가기를 보냈지만 결과를 읽지 못했다. 새로 준비하지 말고 사용자에게 승인 카드와 「내 브라우저」 를 확인해 달라고 한다.").text();

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
            // 같은 대화에서 이어 가는 중인 줄이 있으면 다시 부른 것이다. 새로 준비하지 않게 알린다.
            return conversationId != null && actions.findFirstByUserIdAndConversationIdAndStatusOrderByIdDesc(user.id(), conversationId,
                ActionStatus.EXECUTING).filter(action -> action.waiting() != null).isPresent() ? RUNNING : NONE;
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
            log.warn("이어 가기 맥락 확인 실패 actionId={} kind={}", actionId, ex.getClass().getSimpleName());
            if (temporary(ex)) {
                // 대시보드에 닿지 못했을 수 있다. 기다림을 두면 수명과 일정이 결국 끝낸다. 다시 승인하게 만들지 않는다.
                return RETRY;
            }
            // 연결이나 선언이 바뀌어 같은 승인으로 실행할 수 없다. 기다림을 끝내 새로 준비하게 한다.
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
            return render(user, conversationId, actionId, RUNNING);
        }
        if (started.get().snapshot() == null) {
            return render(user, conversationId, actionId, ENDED);
        }
        approval.run(user, actionId, prepared.context(), prepared.manifest(), started.get().snapshot(), started.get().resume());
        return render(user, conversationId, actionId, SENT);
    }


    /**
     * 플러그인 상태를 읽지 못한 일시 장애인가. 대시보드 호출이 던진 예외와, 그 호출을 감싼 연결 상태 단계의 거절이다. 연결 상태 단계는 상태가
     * 꺼졌을 때도 같은 거절이라 가르지 못한다. 그때도 기다림을 두면 수명이 끝낸다.
     */
    private static boolean temporary(RuntimeException ex) {
        if (ex instanceof FinancialPrepareRejected rejected) {
            return rejected.stage() == FinancialPrepareStage.CONNECTOR_STATE;
        }
        return !(ex instanceof ApiException);
    }


    /**
     * 잠근 줄을 다시 보고 이어 간다. 줄이 바뀌었으면 빈 값, 그 순간의 상한에 걸려 끝냈으면 snapshot 이 없는 값이다(spec DECISION19).
     * 자기 줄을 뺀 오늘 합계로 본다. 뒤의 claim 이 승인 날짜의 합계로 한 번 더 보므로 둘 다 지나야 「구매하기」 를 누른다.
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
            purchases.requireWithin(action.userId(), action.id(), expected, now);
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
     * 빠진다. 모델은 이미 멈춤을 전해 받았으므로 다시 깨우지 않는다. 다음 이어 가기는 이어 갈 승인이 없다는 답을 받아 새로 준비한다.
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
        return Objects.equals(fallback, ENDED) ? ENDED_LEAD + "\n" + input : input;
    }


    private record Prepared(ConnectorExecutionReadContext context, ConnectorExecutionCatalog catalog, ConnectorManifest manifest) {
    }

    private record Started(ConnectorExecutionSnapshot snapshot, ConnectorApprovedExecution.Resume resume) {
    }
}
