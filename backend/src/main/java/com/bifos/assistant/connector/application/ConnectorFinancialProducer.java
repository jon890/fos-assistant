package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionGuard;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionKey;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.HermesToolName;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.proactive.application.CheckNotificationPolicy;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 운영 caller가 없는 금융 준비·저장 규칙이다. 공개 정책의 금융 차단은 그대로 둔다. */
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
    private final TextCipher cipher;
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
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        String key = ConnectorActionKey.of(profileName, rootSessionId, sessionId, toolCallId).value();
        String hash = Sha256.hex(argsJson);
        var recorded = actions.findByDedupeKey(key);
        if (recorded.isPresent()) {
            require(recorded.get().hermesTool().equals(hermesTool) && recorded.get().argsSha256().equals(hash));
            return answer(recorded.get());
        }
        var read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        Context before = read.execute(status -> context(profileName, rootSessionId, sessionId, hermesTool));
        var manifest = manifest(before.connector());
        var contract = guard.read(manifest);
        require(manifest.schema() == 2 && before.server().equals(manifest.mcpServer())
            && HermesToolName.of(manifest.mcpServer(), toolName).equals(hermesTool)
            && contract.operations().containsKey(toolName));
        verified(hermes, manifest, before.profile(), before.binding(), before.connection(), before.connectionRevision());
        // prepare는 재호출하지 않는다. DB 잠금과 연결을 놓은 뒤 읽기 전용 HTTP를 한 번 보낸다.
        var prepared = hermes.prepare(profileName, before.connector(), hermesTool, argsJson);
        require(prepared != null && prepared.ok());
        JsonNode result = prepared.result();
        require(result != null && result.isObject()
            && result.propertyNames().equals(Set.of("v", "executionArgs", "summary"))
            && result.path("v").isInt() && result.path("v").intValue() == 1);
        var fresh = manifest(before.connector());
        require(Objects.equals(manifest.executionGuardManifestSha256(), fresh.executionGuardManifestSha256()));
        verified(hermes, fresh, before.profile(), before.binding(), before.connection(), before.connectionRevision());
        Map<String, String> scope = new LinkedHashMap<>();
        contract.scopeFields().forEach(field -> scope.put(field.arg(), before.fields().get(field.field())));
        String executionArgs = JSON.writeValueAsString(result.get("executionArgs"));
        String summary = JSON.writeValueAsString(result.get("summary"));
        String scopeJson = JSON.writeValueAsString(scope);
        String operation = contract.operations().get(toolName);
        var snapshot = ConnectorExecutionSnapshot.validate(argsJson, executionArgs, summary, scopeJson, operation);
        var tx = new TransactionTemplate(transactionManager);
        ConnectorAction saved = tx.execute(status -> {
            users.findByIdForUpdate(before.user()).orElseThrow(ConnectorFinancialProducer::rejected);
            require(before.equals(context(profileName, rootSessionId, sessionId, hermesTool)));
            var duplicate = actions.findByDedupeKey(key);
            if (duplicate.isPresent()) {
                require(duplicate.get().hermesTool().equals(hermesTool) && duplicate.get().argsSha256().equals(hash));
                return duplicate.get();
            }
            conflicts.requireAvailable(snapshot.requestKey(before.user(), before.connection(), toolName), null);
            var binding = bindings.findById(before.binding()).orElseThrow(ConnectorFinancialProducer::rejected);
            var origin = owners.resolve(profileName, rootSessionId, sessionId);
            Instant now = clock.instant();
            var action = ConnectorAction.decided(binding.connection(), before.agent(), origin, hermesTool, toolName,
                new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.FINANCIAL, ToolApproval.ALWAYS),
                false, key, hash, now);
            action.awaitApproval(argsJson, now.plus(properties.current().approvalTtl()));
            actions.saveAndFlush(action);
            contents.saveAndFlush(ConnectorExecutionSnapshot.capture(action, binding, operation,
                JSON.writeValueAsString(contract.scopeFields()), executionArgs, summary, scopeJson, cipher, now));
            if (action.conversationId() != null && checkNotifications.allows(origin, now)) {
                String title = ConnectorActionView.titleOf(ConnectorToolPolicies.find(manifest, toolName));
                conversations.publicIdOf(action.conversationId()).ifPresent(id -> notifications.notify(action.userId(),
                    NotificationKind.APPROVAL_REQUESTED, ConnectorPolicyService.APPROVAL_REQUESTED_TITLE,
                    "「" + title + "」", new NotificationTarget(NotificationTargetType.CONVERSATION, id)));
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
        var origin = owners.resolve(profile, root, session);
        var matches = bindings.findByAgentId(origin.agentId()).stream()
            .filter(binding -> tool.startsWith(HermesToolName.of(binding.mcpServer(), ""))).toList();
        require(matches.size() == 1);
        ConnectorBinding binding = matches.getFirst();
        var connection = binding.connection();
        var agent = binding.agent();
        require(access.allowed(origin.userId()) && agent.enabled()
            && Objects.equals(agent.ownerUserId(), origin.userId())
            && Objects.equals(connection.userId(), origin.userId()) && profile.equals(agent.hermesProfile())
            && binding.status() == BindingStatus.READY && binding.desiredEnabled() && !binding.restartRequired()
            && connection.status() == ConnectionStatus.READY
            && proactive.checkOf(origin).map(check -> check.writesAllowed()).orElse(true));
        return new Context(origin.id(), origin.userId(), agent.id(), binding.id(), connection.id(), profile,
            connection.connectorId(), binding.mcpServer(), connection.updatedAt(), binding.updatedAt(),
            Map.copyOf(connection.fields().values()));
    }


    private ConnectorManifest manifest(String connector) {
        return ConnectorManifests.find(hermes, connector).orElseThrow(ConnectorFinancialProducer::rejected);
    }


    /** 상태의 verified는 권한이 아니다. 새 선언 및 DB revision과 일치하는지만 확인한다. */
    static void verified(HermesConnectorClient hermes, ConnectorManifest manifest, String profile,
        Long binding, Long connection, Instant revision) {
        var state = hermes.readConnector(profile, manifest.id());
        require(state != null && profile.equals(state.profile()) && state.enabled() && state.configured()
            && state.policyHook() && !state.restartRequired() && HermesConnectorClient.MODE_BIND.equals(state.mode()));
        var node = state.executionGuard();
        require(node != null && "verified".equals(node.path("state").asString())
            && ConnectorExecutionSnapshot.PROTOCOL.equals(node.path("protocol").asString())
            && binding.toString().equals(node.path("bindingId").asString())
            && connection.toString().equals(node.path("connectionId").asString())
            && revision.toString().equals(node.path("connectionUpdatedAt").asString())
            && manifest.executionGuardManifestSha256() != null
            && manifest.executionGuardManifestSha256().matches("[0-9a-f]{64}")
            && manifest.executionGuardManifestSha256().equals(node.path("manifestSha256").asString()));
    }


    static void require(boolean condition) {
        if (!condition) {
            throw rejected();
        }
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
