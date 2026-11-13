package com.bifos.assistant.connector;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.financial.application.ConnectorFinancialConflicts;
import com.bifos.assistant.connector.financial.application.ConnectorFinancialGate;
import com.bifos.assistant.connector.financial.application.PurchaseLimits;
import com.bifos.assistant.connector.financial.application.FinancialDigest;
import com.bifos.assistant.connector.financial.config.ConnectorFinancialProperties;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionClaimCodec;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionClaims;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionCurrent;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionTicket;
import com.bifos.assistant.connector.financial.application.dto.ConnectorExecutionClaimInput;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.HermesToolName;
import com.bifos.assistant.connector.action.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDecision;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionBodies;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.FinancialExecutionOpen;
import com.bifos.assistant.testsupport.OverridableLiveProperties;
import com.bifos.assistant.testsupport.ConnectorExecutionClaimTestSupport;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** 실제 HTTP 와 별도 DB 연결로 claim 을 관측하는 검사의 공용 준비다. 검사 클래스는 이것을 상속하고 다른 검사 클래스는 상속하지 않는다. */
@FinancialExecutionOpen
abstract class ConnectorExecutionClaimBase extends ConnectorExecutionClaimTestSupport {
    static final JsonNode VECTOR = vectors();

    @Autowired
    ConnectorExecutionTicket tickets;

    @Autowired
    ConnectorExecutionClaims claims;

    @Autowired
    ConnectorExecutionCurrent current;

    @Autowired
    ConnectorFinancialConflicts conflicts;

    @Autowired
    LiveProperties<ConnectorFinancialProperties> gate;

    @Autowired
    ConnectorFinancialGate financialGate;
    @Autowired
    PurchaseLimits purchaseLimits;

    @Autowired
    FinancialDigest digest;

    @Autowired
    HermesProperties properties;

    @Autowired
    ConnectorExecutionClaimCodec codec;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    ConnectorActionBodies bodies;

    @Autowired
    ConnectorActionExecutionRepository contents;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorConnectionService connectionService;

    @Autowired
    ConnectorBindingService bindingService;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentExecutionRepository runs;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    TextCipher cipher;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;


    Fixture fixture() {
        return fixture(NOW.plusSeconds(300));
    }


    Fixture fixture(Instant approvalExpiry) {
        return tx().execute(status -> {
            String id = UUID.randomUUID().toString();
            var user = users.saveAndFlush(AppUser.of(id + "@example.test", "검사 주인", 1L, UserRole.MEMBER));
            String profile = "claim-" + id;
            people.saveAndFlush(AllowedPerson.of(user.email(), "검사 주인", profile));
            var agent = agents.saveAndFlush(Agent.of(
                "claim-" + id,
                "검사",
                profile,
                "http://localhost",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id()));
            var connection = ConnectorConnection.pending(user.id(), "demo-financial", NOW);
            connection.connected(new ConnectionFields(Map.of("account", "000007"), Map.of()), NOW);
            connection.ready(NOW);
            connections.saveAndFlush(connection);
            var binding = ConnectorBinding.pending(agent, connection, "demo", NOW);
            binding.installed(false, NOW);
            binding.ready(NOW);
            bindings.saveAndFlush(binding);
            var origin = runs.saveAndFlush(AgentExecution.builder()
                .userId(user.id())
                .agentId(agent.id())
                .profileName(profile)
                .hermesSessionId("fos-" + id)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(NOW)
                .build());
            String args = VECTOR.get("modelArgsJson").stringValue();
            var action = ConnectorAction.decided(
                connection,
                agent.id(),
                origin,
                HermesToolName.of("demo", "place_order"),
                "place_order",
                new ToolPolicyDecision(
                    ActionDecision.NEEDS_APPROVAL, null, ToolRisk.FINANCIAL, ToolApproval.ALWAYS),
                false,
                Sha256.hex(id),
                digest.of("args_json", args),
                NOW);
            action.awaitApproval(approvalExpiry);
            actions.saveAndFlush(action);
            bodies.sealArgs(action, args);
            contents.saveAndFlush(ConnectorExecutionSnapshot.capture(
                action,
                binding,
                "CREATE",
                DECLARATION,
                VECTOR.get("executionArgsJson").stringValue(),
                VECTOR.get("summaryJson").stringValue(),
                VECTOR.get("scopeJson").stringValue(),
                cipher,
                NOW, digest));
            action.beginExecution(NOW);
            // 허용 목록 항목은 계좌와 그 주인 메일이다. 공용 vector 의 계좌를 글자 그대로 이 fixture 의 사용자에게 연다.
            var open = gate.current();
            OverridableLiveProperties.during(gate, new ConnectorFinancialProperties(List.of("000007:" + user.email()),
                open.maxOrderAmountKrw(), open.maxOrderAmountUsd(), open.maxOrderQuantity(), open.maxDailyAmountKrw(),
                open.maxDailyAmountUsd(), open.digestKeyFile()));
            return new Fixture(
                action.publicId(),
                action.id(),
                connection.id(),
                binding.id(),
                agent.code(),
                new CurrentUser(user.id(), user.email(), "검사 주인", 1L, UserRole.MEMBER));
        });
    }


    ConnectorActionExecution row(Fixture fixture) {
        return tx().execute(status -> contents.findById(fixture.actionId()).orElseThrow());
    }


    ConnectorExecutionClaimInput input(String raw) {
        return new ConnectorExecutionClaimInput(
            raw,
            "place_order",
            Sha256.hex(VECTOR.get("executionArgsJson").stringValue()),
            Map.of("account_seq", "000007"));
    }


    TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }


    private static JsonNode vectors() {
        try {
            return JSON.readTree(Files.readString(Path.of("../test/fixtures/financial-approval-v1.json")))
                .get("cases")
                .get(0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }


    record Fixture(UUID action, Long actionId, Long connection, Long binding, String agentCode, CurrentUser user) {
    }
}
