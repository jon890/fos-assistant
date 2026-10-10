package com.bifos.assistant.connector.application.execution;

import com.bifos.assistant.connector.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.application.ConnectorManifests;
import com.bifos.assistant.connector.application.ConnectorToolPolicies;
import com.bifos.assistant.connector.application.model.ConnectorExecutionCatalog;
import com.bifos.assistant.connector.application.model.ConnectorExecutionReadContext;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.domain.HermesToolName;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** DB 읽기, HTTP, 잠금 뒤 최신 상태 검증을 서로 다른 경계에서 실행한다. */
@Service
@RequiredArgsConstructor
public class ConnectorExecutionCurrent {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final ConnectorConnectionRepository connections;
    private final ConnectorBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final UserAccessPolicy access;
    private final ProactiveCheckGuard proactive;
    private final TextCipher cipher;
    private final HermesConnectorClient hermes;
    private final ConnectorExecutionGuard guard;
    private final PlatformTransactionManager transactionManager;

    public ConnectorExecutionReadContext readContext(UUID publicId) {
        requireNoTransaction();
        var read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        try {
            return read.execute(status -> {
                var action = actions.findByPublicId(publicId).orElseThrow(ConnectorExecutionCurrent::rejected);
                var row = contents.findById(action.id()).orElseThrow(ConnectorExecutionCurrent::rejected);
                return context(action, row);
            });
        } catch (RuntimeException ignored) {
            throw rejected();
        }
    }

    public ConnectorExecutionCatalog readCatalog(ConnectorExecutionReadContext context) {
        requireNoTransaction();
        try {
            var manifest = ConnectorManifests.find(hermes, context.connectorId())
                    .orElseThrow(ConnectorExecutionCurrent::rejected);
            var policies = new LinkedHashMap<String, ToolPolicy>();
            manifest.tools()
                    .forEach(t -> policies.put(
                            t.name(),
                            ConnectorToolPolicies.find(manifest, t.name())
                                    .orElseThrow(ConnectorExecutionCurrent::rejected)));
            return new ConnectorExecutionCatalog(
                    manifest.id(),
                    manifest.mcpServer(),
                    guard.read(manifest),
                    policies,
                    manifest.fields().stream()
                            .filter(f -> !f.secret())
                            .map(f -> f.key())
                            .collect(Collectors.toSet()));
        } catch (RuntimeException ignored) {
            throw rejected();
        }
    }

    public ConnectorExecutionSnapshot validateLocked(
            ConnectorExecutionReadContext expected,
            ConnectorExecutionCatalog catalog,
            ConnectorAction action,
            ConnectorActionExecution row) {
        require(TransactionSynchronizationManager.isActualTransactionActive());
        try {
            var latest = context(action, row);
            require(expected.equals(latest));
            require(catalog.connectorId().equals(latest.connectorId())
                    && catalog.mcpServer().equals(latest.mcpServer())
                    && catalog.guard().protocol().equals(row.protocol())
                    && HermesToolName.of(catalog.mcpServer(), action.toolName()).equals(action.hermesTool()));
            ToolPolicy policy = catalog.toolPolicies().get(action.toolName());
            require(policy != null
                    && policy.risk() == ToolRisk.FINANCIAL
                    && policy.approval() == ToolApproval.ALWAYS
                    && action.risk() == ToolRisk.FINANCIAL
                    && action.approvalMode() == ToolApproval.ALWAYS);
            var snapshot = ConnectorExecutionSnapshot.open(action, row, cipher);
            String operation = catalog.guard().operations().get(action.toolName());
            require(operation != null
                    && operation.equals(JSON.readTree(snapshot.summaryJson())
                            .get("operation")
                            .stringValue()));
            require(catalog.guard().scopeFields().stream()
                    .allMatch(f -> catalog.publicFieldNames().contains(f.field())));
            snapshot.validateDeclaredScope(
                    JSON.writeValueAsString(catalog.guard().scopeFields()), latest.publicFields());
            return snapshot;
        } catch (RuntimeException ignored) {
            throw rejected();
        }
    }

    private ConnectorExecutionReadContext context(ConnectorAction action, ConnectorActionExecution row) {
        var connection = connections.findById(row.connectionId()).orElseThrow(ConnectorExecutionCurrent::rejected);
        var binding = bindings.findById(row.bindingId()).orElseThrow(ConnectorExecutionCurrent::rejected);
        var agent = binding.agent();
        require(access.allowed(action.userId())
                && connection.status() == ConnectionStatus.READY
                && binding.status() == BindingStatus.READY
                && binding.desiredEnabled());
        require(Objects.equals(action.id(), row.actionId())
                && Objects.equals(action.userId(), connection.userId())
                && Objects.equals(action.agentId(), agent.id())
                && Objects.equals(agent.ownerUserId(), action.userId())
                && Objects.equals(binding.connection().id(), connection.id())
                && Objects.equals(action.connectorId(), connection.connectorId())
                && Objects.equals(connection.updatedAt(), row.connectionUpdatedAt())
                && Objects.equals(binding.updatedAt(), row.bindingUpdatedAt()));
        var origin = executions.findById(action.originExecutionId()).orElseThrow(ConnectorExecutionCurrent::rejected);
        require(Objects.equals(origin.userId(), action.userId()) && Objects.equals(origin.agentId(), action.agentId()));
        require(proactive.checkOf(origin).map(check -> check.writesAllowed()).orElse(true));
        return new ConnectorExecutionReadContext(
                action.publicId(),
                action.id(),
                action.userId(),
                action.agentId(),
                action.originExecutionId(),
                connection.id(),
                binding.id(),
                agent.hermesProfile(),
                action.connectorId(),
                action.toolName(),
                action.hermesTool(),
                binding.mcpServer(),
                row.protocol(),
                connection.updatedAt(),
                binding.updatedAt(),
                connection.fields().values());
    }

    static void requireNoTransaction() {
        require(!TransactionSynchronizationManager.isActualTransactionActive());
    }

    static void require(boolean condition) {
        if (!condition) {
            throw rejected();
        }
    }

    static ApiException rejected() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "financial execution unavailable");
    }
}
