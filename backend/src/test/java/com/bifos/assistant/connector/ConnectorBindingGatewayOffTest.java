package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * 브라우저 중계가 꺼진 Control Plane 이 사용자 브라우저를 쓰는 커넥터를 붙일 때 주소 자리에 무엇을 싣는지 본다(ADR-20261008 /
 * browser-gateway-token).
 *
 * <p>null 은 사용자 브라우저를 쓰지 않는 커넥터의 값이라 본문에서 키가 빠진다. 선언한 커넥터는 중계가 꺼져도 빈 문자열로 키를 실어,
 * 대시보드가 설치를 막지 않고 빈 값을 넣게 한다. 중계 설정 두 개를 비워 중계를 끈다. 대시보드의 커넥터, 스킬 경로만 대역이다.
 */
@BackendIntegrationTest
@OverrideProperties({"assistant.browser.gateway-base-url=", "assistant.browser.gateway-secret="})
class ConnectorBindingGatewayOffTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String BROWSER = "demo-browser";
    /** 사용자 브라우저를 쓰는 커넥터다. */
    private static final ConnectorManifest BROWSER_MANIFEST = new ConnectorManifest(
            BROWSER,
            "브라우저 메모",
            "",
            List.of(),
            "ping",
            "browser",
            List.of(),
            false,
            1,
            List.of(),
            List.of(),
            ConnectorAppearance.NONE,
            false,
            true,
            "https://login.example.test/sign-in");

    @Autowired
    ConnectorBindingService service;

    @Autowired
    ConnectorConnectionService connectionService;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    HermesSkillClient skills;

    @BeforeEach
    void setUp() {
        when(connector.readCatalog()).thenReturn(List.of(BROWSER_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap(), nullable(String.class)))
                .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(new InstallResult(true, false));
        when(skills.list(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        actions.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("중계가 꺼져 주소가 없으면 사용자 브라우저를 쓰는 커넥터의 붙이기에 null 이 아니라 빈 문자열을 싣는다")
    void bindsBrowserConnectorWithEmptyAddressWhenGatewayIsOff() {
        CurrentUser owner = user();
        Agent agent = agent(owner);
        connectionService.register(owner, BROWSER, Map.of());
        ConnectorConnection connection =
                connections.findByUserIdAndConnectorId(owner.id(), BROWSER).orElseThrow();

        assertThat(service.bind(owner, agent.code(), BROWSER).bound()).isTrue();

        verify(connector).bindConnector(agent.hermesProfile(), BROWSER, connection.vault(), agent.sandboxOwner(), "");
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), isNull());
        verify(connector).call(eq(BROWSER), eq("ping"), eq(Map.of()), eq(""));
    }

    private Agent agent(CurrentUser owner) {
        String code = "gw-off-" + UUID.randomUUID().toString().substring(0, 13);
        return agents.save(Agent.of(
                code,
                code,
                "profile-" + code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                Instant.now()));
    }

    private CurrentUser user() {
        String suffix = UUID.randomUUID().toString();
        AppUser saved = users.save(
                AppUser.of("gateway-off-" + suffix + "@example.com", suffix, 1L, UserRole.MEMBER, Instant.now()));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
