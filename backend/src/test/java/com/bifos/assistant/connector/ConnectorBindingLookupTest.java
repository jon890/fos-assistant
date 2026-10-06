package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 붙은 연결의 서버 이름과 도구 이름 앞부분을 바인딩 행으로만 읽는지 본다. 대시보드는 대역이고 불리지 않아야 한다. */
@SpringBootTest
@ActiveProfiles("test")
class ConnectorBindingLookupTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    AgentConnectorBindings lookup;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @MockitoBean
    HermesConnectorClient connector;

    @AfterEach
    void tearDown() {
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("붙은 연결의 서버 이름과 등록 이름 앞부분을 바인딩 행으로 만들고 카탈로그를 부르지 않는다")
    void readsServersAndPrefixesFromBindingRowsWithoutCatalog() {
        AppUser owner = user();
        Agent agent = agent(owner, false);
        bind(agent, connection(owner, "demo-notes"), "demo-notes");
        bind(agent, connection(owner, "demo-mail"), "mail");

        assertThat(lookup.hasBindings(agent.id())).isTrue();
        assertThat(lookup.connectorServers(agent.id())).containsExactly("demo-notes", "mail");
        // 서버 이름의 - 는 Hermes 등록 이름에서 _ 로 바뀐다.
        assertThat(lookup.connectorToolPrefixes(agent.id())).containsExactly("mcp__demo_notes__", "mcp__mail__");
        verifyNoInteractions(connector);
    }

    @Test
    @DisplayName("붙은 연결이 없으면 비어 있고, 이름을 모르는 옛 바인딩은 있다고만 답한다")
    void returnsEmptyWithoutBindingsAndSkipsUnnamedLegacyBinding() {
        AppUser owner = user();
        Agent plain = agent(owner, false);
        Agent legacy = agent(owner, true);
        bind(legacy, connection(owner, "demo-notes"), null);

        assertThat(lookup.hasBindings(plain.id())).isFalse();
        assertThat(lookup.connectorServers(plain.id())).isEmpty();
        assertThat(lookup.hasBindings(legacy.id())).isTrue();
        assertThat(lookup.connectorServers(legacy.id())).isEmpty();
        assertThat(lookup.connectorToolPrefixes(legacy.id())).isEmpty();
        verifyNoInteractions(connector);
    }

    @Test
    @DisplayName("등록 이름이 잘리는 긴 서버 이름의 앞부분은 Hermes 가 남기는 55자까지다")
    void cutsPrefixOfLongServerNameAtKeptLength() {
        AppUser owner = user();
        Agent agent = agent(owner, false);
        String server = "s".repeat(60);
        bind(agent, connection(owner, "demo-long"), server);

        assertThat(lookup.connectorToolPrefixes(agent.id())).containsExactly(("mcp__" + server).substring(0, 55));
    }

    private void bind(Agent agent, ConnectorConnection connection, String server) {
        bindings.save(ConnectorBinding.pending(agent, connection, server, NOW));
    }

    private ConnectorConnection connection(AppUser owner, String connectorId) {
        return connections.save(ConnectorConnection.pending(owner.id(), connectorId, NOW));
    }

    private Agent agent(AppUser owner, boolean connectorManaged) {
        String code = "lookup-" + UUID.randomUUID().toString().substring(0, 12);
        Agent agent = Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW);
        if (connectorManaged) {
            agent.markConnectorManaged();
        }
        return agents.save(agent);
    }

    private AppUser user() {
        String suffix = UUID.randomUUID().toString();
        return users.save(AppUser.of("lookup-" + suffix + "@example.com", suffix, 1L, UserRole.MEMBER, NOW));
    }
}
