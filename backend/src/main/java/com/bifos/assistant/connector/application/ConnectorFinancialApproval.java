package com.bifos.assistant.connector.application;

import static com.bifos.assistant.connector.application.ConnectorFinancialProducer.require;

import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionCurrent;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionGuard;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionTicket;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorExecutionReadContext;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionBodies;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorApprovedExecution;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.orchestration.config.DelegationProperties;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
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

/** 공개 승인 API 가 위험도 FINANCIAL 줄을 넘기는 금융 승인이다. grant 입력과 자동 재실행을 받지 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
class ConnectorFinancialApproval {
    private final ConnectorExecutionCurrent current;
    private final ConnectorExecutionGuard guard;
    private final ConnectorExecutionTicket tickets;
    private final ConnectorFinancialConflicts conflicts;
    private final ConnectorFinancialGate gate;
    private final AppUserRepository users;
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final HermesConnectorClient hermes;
    private final DecisionFeedbackRecorder feedback;
    private final LiveProperties<DelegationProperties> delegation;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final ApplicationEventPublisher events;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final ConnectorActionBodies bodies;


    ConnectorActionView approve(CurrentUser user, UUID actionId) {
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        gate.requireOpen();
        var owned = actions.findByPublicId(actionId).orElseThrow(ConnectorActionDetails::notFound);
        if (!Objects.equals(user.id(), owned.userId())) {
            throw ConnectorActionDetails.notFound();
        }
        ConnectorExecutionReadContext context = current.readContext(actionId);
        var catalog = current.readCatalog(context);
        var manifest = ConnectorManifests.find(hermes, context.connectorId()).orElseThrow(ConnectorFinancialProducer::rejected);
        require(catalog.guard().equals(guard.read(manifest)));
        ConnectorFinancialProducer.verified(hermes, manifest, context.profile(), context.bindingId(), context.connectionId(),
            context.connectionUpdatedAt());
        var tx = new TransactionTemplate(transactionManager);
        ConnectorExecutionSnapshot snapshot = tx.execute(status -> {
            users.findByIdForUpdate(user.id()).orElseThrow(ConnectorActionDetails::notFound);
            var action = actions.findByPublicIdForUpdate(actionId).orElseThrow(ConnectorActionDetails::notFound);
            require(Objects.equals(action.userId(), user.id()) && action.status() == ActionStatus.PENDING);
            var row = contents.findByActionIdForUpdate(action.id()).orElseThrow(ConnectorFinancialProducer::rejected);
            var checked = current.validateLocked(context, catalog, action, row);
            require(action.expiresAt().isAfter(clock.instant()));
            conflicts.requireAvailable(row.requestKey(), action.id());
            // 준비와 승인 사이의 재기동으로 key 파일 설정을 비우거나 목록과 상한을 줄였으면 여기서 막는다.
            // key 는 기동 뒤 처음 읽은 값을 쓰므로 실행 중에 파일만 지워서는 닫히지 않는다.
            gate.requireOpen();
            gate.requireScope(user.id(), checked.scopeJson());
            gate.requireWithinLimits(user.id(), action.id(), checked.executionArgsJson(), clock.instant());
            action.beginExecution(clock.instant());
            feedback.record(entry(action, FeedbackEventType.APPROVED, FeedbackActor.USER));
            return checked;
        });
        CallResult result = null;
        String ticket = null;
        try {
            ticket = tickets.issue(actionId);
        } catch (RuntimeException failure) {
            // 발급 실패는 실행 HTTP를 보내지 않은 것이 분명하다. 재발급하지 않는다. 원문과 ticket은 남기지 않는다.
            log.warn("금융 실행 ticket 발급 실패 actionId={} kind={}", actionId, failure.getClass().getSimpleName());
            result = CallResult.failure(ConnectorCallError.UNAVAILABLE);
        }
        if (ticket != null) {
            try {
                result = hermes.executeApproved(context.profile(), context.connectorId(), context.hermesTool(),
                    new ConnectorApprovedExecution(ticket, snapshot.executionArgsJson(), snapshot.executionArgsSha256()));
            } catch (RuntimeException failure) {
                // 전송 응답을 잃으면 UNKNOWN으로 남기고 다시 실행하지 않는다.
                log.warn("금융 실행 결과 불명 actionId={} kind={}", actionId, failure.getClass().getSimpleName());
            }
        }
        CallResult executed = result;
        ConnectorAction finished = tx.execute(status -> {
            users.findByIdForUpdate(user.id()).orElseThrow(ConnectorActionDetails::notFound);
            var action = actions.findByPublicIdForUpdate(actionId).orElseThrow(ConnectorActionDetails::notFound);
            if (action.status() == ActionStatus.EXECUTING) {
                var row = contents.findByActionIdForUpdate(action.id()).orElseThrow(ConnectorFinancialProducer::rejected);
                if (executed != null && executed.ok()) {
                    if (row.consumedAt() == null) {
                        log.warn("claim 없이 금융 성공을 받았다 actionId={}", actionId);
                    }
                    bodies.succeed(action, clip(executed.result().toString()), clock.instant());
                    feedback.record(entry(action, FeedbackEventType.EXECUTION_SUCCEEDED, FeedbackActor.SYSTEM));
                } else {
                    ConnectorFinancialOutcome.settle(action, row, executed, bodies, clock.instant());
                    if (action.status() == ActionStatus.FAILED) {
                        feedback.record(entry(action, FeedbackEventType.EXECUTION_FAILED, FeedbackActor.SYSTEM).reason(action.errorCode()));
                    }
                }
                new ConnectorActionSignals(events, notifications, conversations).publishAfterCommit(List.of(action));
            }
            return action;
        });
        return ConnectorActionDetails.view(finished, Optional.of(manifest));
    }


    private FeedbackEntry entry(ConnectorAction action, FeedbackEventType type, FeedbackActor actor) {
        return FeedbackEntry.of(action.userId(), FeedbackSubjectType.CONNECTOR_ACTION, action.publicId(), type, actor, clock.instant())
            .conversation(action.conversationId()).originExecution(action.originExecutionId()).version(action.argsSha256());
    }


    private String clip(String text) {
        int max = delegation.current().outputMaxChars();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }
}
