package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.connector.action.application.ConnectorActionSignals;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.financial.application.ConnectorFinancialConflicts;
import com.bifos.assistant.connector.financial.application.ConnectorFinancialGate;
import com.bifos.assistant.connector.financial.application.FinancialDigest;
import com.bifos.assistant.connector.financial.application.FinancialLimitExceeded;
import com.bifos.assistant.connector.connection.application.ConnectorManifests;
import com.bifos.assistant.connector.connection.application.ConnectorToolPolicies;
import com.bifos.assistant.connector.connection.config.ConnectorPolicyProperties;
import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionGuard;
import com.bifos.assistant.connector.action.application.dto.ConnectorActionView;
import com.bifos.assistant.connector.approval.application.dto.FinancialPrepareStage;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ConnectorActionKey;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.HermesToolName;
import com.bifos.assistant.connector.action.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDecision;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionBodies;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.dto.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.proactive.check.application.port.CheckNotificationPolicy;
import com.bifos.assistant.proactive.check.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정책 판정이 준비 도구 호출을 넘기는 금융 준비·저장 규칙이다. 실행 도구를 직접 부른 호출의 금융 차단은 그대로 둔다.
 *
 * <p>거절은 사용자와 모델에게 어느 검사에 걸렸는지 밝히지 않는다. 운영 로그에는 고정 단계 코드({@link FinancialPrepareStage})만 남긴다.
 */
@Component
@RequiredArgsConstructor
class ConnectorFinancialProducer {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SessionOwnerResolver owners;
    private final ConnectorBindingRepository bindings;
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final AppUserRepository users;
    private final UserAccessPolicy access;
    private final ProactiveCheckGuard proactive;
    private final ConnectorExecutionGuard guard;
    private final ConnectorFinancialConflicts conflicts;
    private final ConnectorFinancialGate gate;
    private final FinancialDigest digest;
    private final TextCipher cipher;
    private final ConnectorActionBodies bodies;
    private final HermesConnectorClient hermes;
    private final LiveProperties<ConnectorPolicyProperties> properties;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final CheckNotificationPolicy checkNotifications;
    private final DecisionFeedbackRecorder feedback;
    private final ApplicationEventPublisher events;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;


    ConnectorPolicyAnswer request(String profileName, String rootSessionId, String sessionId,
        String toolCallId, String hermesTool, String toolName, String argsJson) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), FinancialPrepareStage.TRANSACTION);
        // 금융 snapshot은 평문으로 저장하지 않는다. 데이터 key가 꺼져 있으면 준비 HTTP 전에 거절한다.
        require(cipher.enabled(), FinancialPrepareStage.CIPHER);
        at(FinancialPrepareStage.GATE, gate::requireOpen);
        String key = ConnectorActionKey.of(profileName, rootSessionId, sessionId, toolCallId).value();
        // 금융 줄의 인자 해시도 keyed hash 로 적는다. 판단 피드백의 version 도 이 값을 복사한다.
        String hash = digest.of("args_json", argsJson);
        var recorded = actions.findByDedupeKey(key);
        if (recorded.isPresent()) {
            require(recorded.get().hermesTool().equals(hermesTool) && recorded.get().argsSha256().equals(hash), FinancialPrepareStage.REPLAY);
            return answer(recorded.get());
        }
        var read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        Context before = read.execute(status -> context(profileName, rootSessionId, sessionId, hermesTool));
        var manifest = manifest(before.connector());
        var contract = at(FinancialPrepareStage.MANIFEST, () -> guard.read(manifest));
        require(manifest.schema() == 2 && before.server().equals(manifest.mcpServer())
            && HermesToolName.of(manifest.mcpServer(), toolName).equals(hermesTool)
            && contract.operations().containsKey(toolName), FinancialPrepareStage.MANIFEST);
        verified(hermes, manifest, before.profile(), before.binding(), before.connection(), before.connectionRevision());
        Map<String, String> scope = new LinkedHashMap<>();
        contract.scopeFields().forEach(field -> scope.put(field.arg(), before.fields().get(field.field())));
        // 허용 목록 밖의 계좌는 토스 읽기도 보내지 않는다.
        at(FinancialPrepareStage.ACCOUNTS, () -> gate.requireAccounts(before.user(), scope.values()));
        // prepare는 재호출하지 않는다. DB 잠금과 연결을 놓은 뒤 읽기 전용 HTTP를 한 번 보낸다.
        var prepared = at(FinancialPrepareStage.PREPARE_HTTP, () -> hermes.prepare(profileName, before.connector(), hermesTool, argsJson));
        require(prepared != null && prepared.ok(), FinancialPrepareStage.PREPARE_HTTP);
        JsonNode result = prepared.result();
        require(result != null && result.isObject()
            && result.propertyNames().equals(Set.of("v", "executionArgs", "summary"))
            && result.path("v").isInt() && result.path("v").intValue() == 1, FinancialPrepareStage.PREPARE_SHAPE);
        var fresh = manifest(before.connector());
        require(Objects.equals(manifest.executionGuardManifestSha256(), fresh.executionGuardManifestSha256()), FinancialPrepareStage.MANIFEST_CHANGED);
        verified(hermes, fresh, before.profile(), before.binding(), before.connection(), before.connectionRevision());
        String executionArgs = JSON.writeValueAsString(result.get("executionArgs"));
        String summary = JSON.writeValueAsString(result.get("summary"));
        String scopeJson = JSON.writeValueAsString(scope);
        String operation = contract.operations().get(toolName);
        var snapshot = at(FinancialPrepareStage.SNAPSHOT, () -> ConnectorExecutionSnapshot.validate(
            argsJson, executionArgs, summary, scopeJson, operation, contract.kind(toolName)));
        var tx = new TransactionTemplate(transactionManager);
        ConnectorAction saved = tx.execute(status -> {
            require(users.findByIdForUpdate(before.user()).isPresent(), FinancialPrepareStage.USER);
            require(before.equals(context(profileName, rootSessionId, sessionId, hermesTool)), FinancialPrepareStage.CONTEXT_CHANGED);
            var duplicate = actions.findByDedupeKey(key);
            if (duplicate.isPresent()) {
                require(duplicate.get().hermesTool().equals(hermesTool) && duplicate.get().argsSha256().equals(hash), FinancialPrepareStage.REPLAY);
                return duplicate.get();
            }
            at(FinancialPrepareStage.CONFLICT, () -> conflicts.requireAvailable(
                snapshot.storedRequestKey(digest, before.user(), before.connector(), toolName), null));
            at(FinancialPrepareStage.GATE, gate::requireOpen);
            at(FinancialPrepareStage.LIMITS, () -> gate.requireWithinLimits(before.user(), null, executionArgs, clock.instant()));
            var binding = bindings.findById(before.binding()).orElseThrow(() -> new FinancialPrepareRejected(FinancialPrepareStage.BINDING));
            var origin = owners.resolve(profileName, rootSessionId, sessionId);
            Instant now = clock.instant();
            var action = ConnectorAction.decided(binding.connection(), before.agent(), origin, hermesTool, toolName,
                new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.FINANCIAL, ToolApproval.ALWAYS),
                false, key, hash, now);
            action.awaitApproval(now.plus(properties.current().approvalTtl()));
            actions.saveAndFlush(action);
            bodies.sealArgs(action, argsJson);
            var row = ConnectorExecutionSnapshot.capture(action, binding, operation,
                JSON.writeValueAsString(contract.scopeFields()), executionArgs, summary, scopeJson, cipher, now, digest);
            require(row.contentKeyId() != null, FinancialPrepareStage.CONTENT_KEY);
            contents.saveAndFlush(row);
            if (action.conversationId() != null && checkNotifications.allows(origin, now)) {
                String title = ConnectorActionView.titleOf(ConnectorToolPolicies.find(manifest, toolName));
                conversations.publicIdOf(action.conversationId()).ifPresent(id -> notifications.notify(action.userId(),
                    NotificationKind.APPROVAL_REQUESTED, ConnectorActionSignals.APPROVAL_REQUESTED_TITLE,
                    "「" + title + "」", new NotificationTarget(NotificationTargetType.CONVERSATION, id),
                    action.publicId()));
            }
            new ConnectorActionSignals(events, notifications, conversations).publishAfterCommit(List.of(action));
            feedback.record(FeedbackEntry.of(action.userId(), FeedbackSubjectType.CONNECTOR_ACTION, action.publicId(),
                FeedbackEventType.SURFACED, FeedbackActor.AGENT, now).conversation(action.conversationId())
                .originExecution(action.originExecutionId()).version(action.argsSha256()));
            return action;
        });
        return answer(saved);
    }


    private Context context(String profile, String root, String session, String tool) {
        var origin = at(FinancialPrepareStage.SESSION, () -> owners.resolve(profile, root, session));
        var matches = bindings.findByAgentId(origin.agentId()).stream()
            .filter(binding -> tool.startsWith(HermesToolName.of(binding.mcpServer(), ""))).toList();
        require(matches.size() == 1, FinancialPrepareStage.BINDING);
        ConnectorBinding binding = matches.getFirst();
        var connection = binding.connection();
        var agent = binding.agent();
        require(access.allowed(origin.userId()) && agent.enabled()
            && Objects.equals(agent.ownerUserId(), origin.userId())
            && Objects.equals(connection.userId(), origin.userId()) && profile.equals(agent.hermesProfile()), FinancialPrepareStage.OWNER);
        require(binding.status() == BindingStatus.READY && binding.desiredEnabled() && !binding.restartRequired(),
            FinancialPrepareStage.BINDING_STATE);
        require(connection.status() == ConnectionStatus.READY, FinancialPrepareStage.CONNECTION_STATE);
        require(proactive.checkOf(origin).map(check -> check.writesAllowed()).orElse(true), FinancialPrepareStage.CHECK);
        return new Context(origin.id(), origin.userId(), agent.id(), binding.id(), connection.id(), profile,
            connection.connectorId(), binding.mcpServer(), connection.updatedAt(), binding.updatedAt(),
            Map.copyOf(connection.fields().values()));
    }


    private ConnectorManifest manifest(String connector) {
        return ConnectorManifests.find(hermes, connector).orElseThrow(() -> new FinancialPrepareRejected(FinancialPrepareStage.MANIFEST));
    }


    /** 상태의 verified는 권한이 아니다. 새 선언 및 DB revision과 일치하는지만 확인한다. */
    static void verified(HermesConnectorClient hermes, ConnectorManifest manifest, String profile,
        Long binding, Long connection, Instant revision) {
        var state = at(FinancialPrepareStage.CONNECTOR_STATE, () -> hermes.readConnector(profile, manifest.id()));
        require(state != null && profile.equals(state.profile()) && state.enabled() && state.configured()
            && state.policyHook() && !state.restartRequired() && HermesConnectorClient.MODE_BIND.equals(state.mode()),
            FinancialPrepareStage.CONNECTOR_STATE);
        var node = state.executionGuard();
        require(node != null && "verified".equals(node.path("state").asString())
            && ConnectorExecutionSnapshot.PROTOCOL.equals(node.path("protocol").asString()), FinancialPrepareStage.GUARD_STATE);
        require(binding.toString().equals(node.path("bindingId").asString())
            && connection.toString().equals(node.path("connectionId").asString())
            && revision.toString().equals(node.path("connectionUpdatedAt").asString()), FinancialPrepareStage.GUARD_REVISION);
        require(manifest.executionGuardManifestSha256() != null
            && manifest.executionGuardManifestSha256().matches("[0-9a-f]{64}")
            && manifest.executionGuardManifestSha256().equals(node.path("manifestSha256").asString()), FinancialPrepareStage.GUARD_MANIFEST);
    }


    static void require(boolean condition) {
        if (!condition) {
            throw rejected();
        }
    }


    private static void require(boolean condition, FinancialPrepareStage stage) {
        if (!condition) {
            throw new FinancialPrepareRejected(stage);
        }
    }


    /** 다른 객체가 던진 거절에 이 단계를 붙인다. 상한 거절은 이유를 밝히는 답이 따로 있어 그대로 둔다. */
    private static <T> T at(FinancialPrepareStage stage, Supplier<T> step) {
        try {
            return step.get();
        } catch (FinancialLimitExceeded | FinancialPrepareRejected ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 원래 예외의 메시지에는 원격 응답과 인자가 섞일 수 있어 cause 로도 싣지 않는다.
            throw new FinancialPrepareRejected(stage);
        }
    }


    private static void at(FinancialPrepareStage stage, Runnable step) {
        at(stage, () -> {
            step.run();
            return null;
        });
    }


    static ApiException rejected() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "financial preparation unavailable");
    }


    private static ConnectorPolicyAnswer answer(ConnectorAction action) {
        return new ConnectorPolicyAnswer(false, "금융 요청은 매번 별도 승인이 필요합니다.", action.publicId());
    }


    private record Context(Long origin, Long user, Long agent, Long binding, Long connection, String profile,
        String connector, String server, Instant connectionRevision, Instant bindingRevision, Map<String, String> fields) {
    }
}
