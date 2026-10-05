package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.bifos.assistant.connector.presentation.AgentConnectionController;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트 상세의 연결 경로가 주인만 붙이고 떼게 하고, 응답에 설치의 내부 이름을 싣지 않는지 본다(ADR-083).
 *
 * <p>권한은 실제 바인딩 서비스와 저장소로 판정한다. 로그인 사용자와 대시보드의 커넥터, 스킬, 도구 목록 경로만 대역이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentConnectionControllerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DEMO = "demo-notes";
    private static final String TOKEN = "demo_ok_0123456789";
    /** 응답에 나오면 안 되는 이름이다. 커넥터 번호와 겹치지 않게 둔다. */
    private static final String ENV_NAME = "MEMO_SECRET_ENV";

    private static final String SERVER_NAME = "memo-server-internal";
    private static final ConnectorManifest MANIFEST = new ConnectorManifest(
            DEMO,
            "검사용 메모",
            "",
            List.of(new ConnectorField("token", ENV_NAME, "토큰", "", true, true, null, null)),
            "list_scopes",
            SERVER_NAME,
            List.of(),
            false,
            2,
            List.of(
                    new ConnectorTool("list_scopes", "READ", "none", null, null),
                    new ConnectorTool("write_note", "WRITE", "required", null, null)),
            List.of());

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

    @MockitoBean
    HermesConnectorClient connector;

    @MockitoBean
    HermesSkillClient skills;

    @MockitoBean
    HermesToolsetClient toolsets;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(connector.readCatalog()).thenReturn(List.of(MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.bindConnector(anyString(), anyString(), anyString())).thenReturn(new InstallResult(true, false));
        when(connector.unbindConnector(anyString(), anyString())).thenReturn(new InstallResult(false, false));
        when(connector.putConnector(anyString(), anyString(), anyBoolean()))
                .thenReturn(new InstallResult(false, false));
        when(connector.readConnector(anyString(), anyString()))
                .thenReturn(new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(skills.list(anyString())).thenReturn(List.of());
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web"));
        mvc = MockMvcBuilders.standaloneSetup(new AgentConnectionController(service, currentUser))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
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
    @DisplayName("주인은 연결을 붙이고 떼며 응답에 env 이름과 보관 파일 이름과 서버 이름이 없다")
    void ownerBindsAndUnbindsWithoutInternalNames() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER);
        ConnectorConnection connection = connect(owner);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        signIn(owner);
        String path = "/api/v1/agents/" + agent.code() + "/connections";

        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedReason").doesNotExist())
                .andExpect(jsonPath("$.connections[0].connectorId").value(DEMO))
                .andExpect(jsonPath("$.connections[0].bound").value(false))
                .andExpect(jsonPath("$.connections[0].status").doesNotExist());

        String bound = mvc.perform(put(path + "/" + DEMO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectorId").value(DEMO))
                .andExpect(jsonPath("$.title").value("검사용 메모"))
                .andExpect(jsonPath("$.connectionStatus").value("READY"))
                .andExpect(jsonPath("$.bound").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.restartRequired").value(true))
                .andExpect(jsonPath("$.toolCount").value(2))
                .andExpect(jsonPath("$.vault").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String listed = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections[0].bound").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(bound + listed).doesNotContain(ENV_NAME, SERVER_NAME, connection.vault(), TOKEN);
        verify(connector).bindConnector(agent.hermesProfile(), DEMO, connection.vault());

        mvc.perform(delete(path + "/" + DEMO)).andExpect(status().isNoContent());

        verify(connector).unbindConnector(agent.hermesProfile(), DEMO);
        assertThat(bindings.findByAgentId(agent.id())).isEmpty();
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections[0].bound").value(false));
    }

    @Test
    @DisplayName("남의 비공개 에이전트에 붙이면 AGENT_NOT_FOUND 다")
    void rejectsOthersPrivateAgentAsNotFound() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER);
        CurrentUser other = user(UserRole.MEMBER);
        connect(other);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        signIn(other);

        mvc.perform(put("/api/v1/agents/" + agent.code() + "/connections/" + DEMO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("읽을 수 있는 남의 그룹 공개 에이전트에 붙이면 FORBIDDEN 이다")
    void rejectsOthersReadableAgentAsForbidden() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER);
        CurrentUser other = user(UserRole.MEMBER);
        connect(other);
        Agent agent = agent(owner, AgentVisibility.GROUP);
        signIn(other);

        mvc.perform(put("/api/v1/agents/" + agent.code() + "/connections/" + DEMO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("관리자도 남의 비공개 에이전트의 연결 목록은 FORBIDDEN 이다")
    void rejectsAdminOnOthersAgentAsForbidden() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER);
        CurrentUser admin = user(UserRole.ADMIN);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        signIn(admin);

        mvc.perform(get("/api/v1/agents/" + agent.code() + "/connections"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("연결이 없는 주인의 그룹 공개 에이전트는 빈 목록과 붙일 수 없는 까닭을 준다")
    void listsNoConnectionsWithBlockedReasonForGroupAgent() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER);
        Agent agent = agent(owner, AgentVisibility.GROUP);
        signIn(owner);

        mvc.perform(get("/api/v1/agents/" + agent.code() + "/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections").isEmpty())
                .andExpect(jsonPath("$.blockedReason").value("AGENT_NOT_PRIVATE"));
    }

    private void signIn(CurrentUser user) {
        when(currentUser.require()).thenReturn(user);
    }

    private ConnectorConnection connect(CurrentUser owner) {
        connectionService.register(owner, DEMO, Map.of("token", TOKEN));
        return connections.findByUserIdAndConnectorId(owner.id(), DEMO).orElseThrow();
    }

    private Agent agent(CurrentUser owner, AgentVisibility visibility) {
        String code = "conn-" + UUID.randomUUID().toString().substring(0, 13);
        return agents.save(Agent.of(
                code,
                code,
                "profile-" + code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                owner.id(),
                Instant.now()));
    }

    private CurrentUser user(UserRole role) {
        String suffix = UUID.randomUUID().toString();
        AppUser saved =
                users.save(AppUser.of("agent-connection-" + suffix + "@example.com", suffix, 1L, role, Instant.now()));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
