package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.application.SpecialistAgentCreator;
import com.bifos.assistant.connector.connection.application.dto.SpecialistAgent;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.dto.SoulDocument;
import com.bifos.assistant.hermes.application.port.ConnectorProfileRejected;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.application.port.HermesDashboardClient;
import com.bifos.assistant.hermes.application.port.HermesSkillClient;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.hermes.infra.HermesProfileKeyStore;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.mcp.application.dto.McpCallContext;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 연결 하나만 맡는 전문 에이전트를 한 요청으로 만들고, 실패하면 거두는지 본다(docs/features/agent-team/spec.md 의 REQ5, REQ6).
 *
 * <p>에이전트 만들기, 성격 쓰기, 붙이기, 떼기는 실제 빈이다. 대시보드, 커넥터, 스킬, 도구 목록 경로만 대역이다.
 */
@BackendIntegrationTest
class SpecialistAgentCreatorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DEMO = "demo-notes";
    private static final String SINGLE = "demo-single";
    private static final Map<String, String> VALUES = Map.of("token", "demo_ok_0123456789");
    private static final ConnectorManifest DEMO_MANIFEST = new ConnectorManifest(
        DEMO,
        "검사용 메모",
        "",
        List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null)),
        "list_scopes",
        "demo",
        2,
        List.of(new ConnectorTool("list_scopes", "READ", "none", null, null)),
        List.of());
    private static final ConnectorManifest SINGLE_MANIFEST = new ConnectorManifest(
        SINGLE,
        "하나에만 붙는 메모",
        "",
        DEMO_MANIFEST.fields(),
        "list_scopes",
        "single",
        2,
        DEMO_MANIFEST.tools(),
        List.of(),
        ConnectorAppearance.NONE,
        true,
        false,
        null);
    private static final AgentIntroduction INTRODUCTION = AgentIntroduction.of("메모 담당", "메모 일을 맡아요", "📝", null);

    @Autowired
    SpecialistAgentCreator creator;

    @Autowired
    ConnectorBindingService bindingService;

    @Autowired
    ConnectorConnectionService connectionService;

    @Autowired
    McpToolService mcpTools;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    HermesDashboardClient dashboard;

    @Autowired
    HermesSkillClient skills;

    @Autowired
    HermesToolsetClient toolsets;

    @Autowired
    HermesProfileKeyStore keyStore;

    private final List<String> createdProfiles = new ArrayList<>();


    @BeforeEach
    void setUp() {
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST, SINGLE_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap(), nullable(String.class)))
            .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenReturn(new InstallResult(false, false, true));
        when(connector.unbindConnector(anyString(), anyString())).thenReturn(new InstallResult(false, false));
        when(connector.readConnector(anyString(), anyString()))
            .thenReturn(new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(skills.list(anyString())).thenReturn(List.of());
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "todo"));
        when(dashboard.readSoul(anyString())).thenReturn(new SoulDocument("기본 성격", true));
    }


    @AfterEach
    void tearDown() {
        agents.findAll().stream()
            .filter(Agent::profileManaged)
            .map(Agent::hermesProfile)
            .forEach(createdProfiles::add);
        createdProfiles.forEach(keyStore::delete);
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }


    @Test
    @DisplayName("만들면 소개가 채워진 비공개 에이전트에 그 연결이 붙고 성격 틀을 쓴다")
    void createsPrivateAgentWithIntroductionPersonaAndBinding() {
        CurrentUser owner = member();
        connect(owner, DEMO);

        SpecialistAgent made = creator.create(owner, DEMO, INTRODUCTION, true);

        Agent stored = agents.findByCode(made.agentCode()).orElseThrow();
        assertThat(stored.name()).isEqualTo("메모 담당");
        assertThat(stored.description()).isEqualTo("메모 일을 맡아요");
        assertThat(stored.icon()).isEqualTo("📝");
        assertThat(stored.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        assertThat(stored.ownerUserId()).isEqualTo(owner.id());
        assertThat(made.connection().bound()).isTrue();
        assertThat(bindingsOf(stored)).hasSize(1);
        assertThat(made.detachedFrom()).as("붙은 곳이 없으면 뗄 것도 없다").isEmpty();
        verify(dashboard).putSoul(
            stored.hermesProfile(),
            "너는 「메모 담당」이다. 「검사용 메모」 일만 맡는 전문 에이전트다.\n메모 일을 맡아요\n"
                + "이 서비스의 도구와 함께 붙은 스킬로 일하고, 맡은 일이 이 서비스 밖이면 할 수 없다고 짧게 답한다.");
    }


    @Test
    @DisplayName("떼기를 켜면 다른 에이전트의 바인딩이 없어지고 agent_list 에 새 에이전트와 그 서비스가 보인다")
    void detachRemovesOtherBindingAndAgentListShowsSpecialist() {
        CurrentUser owner = member();
        connect(owner, DEMO);
        Agent assistant = plainAgent(owner, "비서");
        bindingService.bind(owner, assistant.code(), DEMO);

        SpecialistAgent made = creator.create(owner, DEMO, INTRODUCTION, true);

        assertThat(bindingsOf(assistant)).isEmpty();
        assertThat(made.detachedFrom()).extracting(summary -> summary.agentName()).containsExactly("비서");
        jdbc.update("UPDATE agent_connector_binding SET status = 'READY'");
        String listed = text(mcpTools.listAgents(
            new McpCaller(owner, mock(AgentExecution.class), mock(McpCallContext.class))));
        assertThat(listed)
            .contains("\"code\":\"" + made.agentCode() + "\",\"name\":\"메모 담당\",\"description\":\"메모 일을 맡아요\","
                + "\"connectors\":[\"검사용 메모\"]");
    }


    @Test
    @DisplayName("떼기를 끄면 다른 에이전트의 바인딩을 그대로 두고 새 에이전트에도 붙인다")
    void withoutDetachKeepsOtherBinding() {
        CurrentUser owner = member();
        connect(owner, DEMO);
        Agent assistant = plainAgent(owner, "비서");
        bindingService.bind(owner, assistant.code(), DEMO);

        SpecialistAgent made = creator.create(owner, DEMO, INTRODUCTION, false);

        assertThat(bindingsOf(assistant)).hasSize(1);
        assertThat(bindingsOf(agents.findByCode(made.agentCode()).orElseThrow())).hasSize(1);
    }


    @Test
    @DisplayName("single_binding 은 떼기 없이 거절하고 아무것도 만들지 않는다")
    void singleBindingWithoutDetachIsRejectedBeforeCreating() {
        CurrentUser owner = member();
        connect(owner, SINGLE);
        Agent assistant = plainAgent(owner, "비서");
        bindingService.bind(owner, assistant.code(), SINGLE);

        assertCode(() -> creator.create(owner, SINGLE, INTRODUCTION, false), ErrorCode.CONNECTOR_SINGLE_BINDING);

        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(owner.id())).isEqualTo(1);
        verify(dashboard, never()).createProfile(anyString());
    }


    @Test
    @DisplayName("single_binding 은 먼저 떼고 새 에이전트에 붙인다")
    void singleBindingDetachesThenBinds() {
        CurrentUser owner = member();
        connect(owner, SINGLE);
        Agent assistant = plainAgent(owner, "비서");
        bindingService.bind(owner, assistant.code(), SINGLE);

        SpecialistAgent made = creator.create(owner, SINGLE, INTRODUCTION, true);

        assertThat(bindingsOf(assistant)).isEmpty();
        assertThat(bindingsOf(agents.findByCode(made.agentCode()).orElseThrow())).hasSize(1);
    }


    @Test
    @DisplayName("single_binding 의 붙이기가 실패하면 새 에이전트를 지우고 뗀 에이전트에 다시 붙인다")
    void singleBindingFailureRebindsDetachedAgent() {
        CurrentUser owner = member();
        connect(owner, SINGLE);
        Agent assistant = plainAgent(owner, "비서");
        bindingService.bind(owner, assistant.code(), SINGLE);
        rejectBindingToNewProfiles();

        assertCode(() -> creator.create(owner, SINGLE, INTRODUCTION, true), ErrorCode.CONNECTOR_PROFILE_NOT_READY);

        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(owner.id())).as("새 에이전트를 지웠다").isEqualTo(1);
        assertThat(bindingsOf(assistant)).as("뗀 에이전트에 다시 붙인다").hasSize(1);
    }


    @Test
    @DisplayName("성격 쓰기가 실패하면 새 에이전트를 지우고 profile 을 거두며 다른 에이전트의 바인딩은 그대로다")
    void personaFailureDeletesNewAgent() {
        CurrentUser owner = member();
        connect(owner, DEMO);
        Agent assistant = plainAgent(owner, "비서");
        bindingService.bind(owner, assistant.code(), DEMO);
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down")).when(dashboard).putSoul(anyString(), anyString());

        assertCode(() -> creator.create(owner, DEMO, INTRODUCTION, true), ErrorCode.HERMES_UNAVAILABLE);

        Agent made = agents.findAll().stream().filter(Agent::profileManaged).findFirst().orElseThrow();
        assertThat(made.isDeleted()).isTrue();
        verify(dashboard).deleteProfile(made.hermesProfile());
        assertThat(bindingsOf(assistant)).hasSize(1);
        verify(connector, never()).bindConnector(eq(made.hermesProfile()), anyString(), anyString(), anyString(),
            nullable(String.class));
    }


    @Test
    @DisplayName("붙이기가 거절되면 새 에이전트를 지우고 다른 에이전트에서 떼지 않는다")
    void bindFailureDeletesNewAgentAndKeepsOtherBinding() {
        CurrentUser owner = member();
        connect(owner, DEMO);
        Agent assistant = plainAgent(owner, "비서");
        bindingService.bind(owner, assistant.code(), DEMO);
        rejectBindingToNewProfiles();

        assertCode(() -> creator.create(owner, DEMO, INTRODUCTION, true), ErrorCode.CONNECTOR_PROFILE_NOT_READY);

        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(owner.id())).isEqualTo(1);
        assertThat(bindingsOf(assistant)).hasSize(1);
    }


    @Test
    @DisplayName("붙인 뒤 다른 에이전트에서 떼다 실패하면 새 에이전트를 지우고 그 에이전트의 바인딩은 그대로다")
    void detachFailureAfterBindDeletesNewAgent() {
        CurrentUser owner = member();
        connect(owner, DEMO);
        Agent assistant = plainAgent(owner, "비서");
        bindingService.bind(owner, assistant.code(), DEMO);
        when(connector.unbindConnector(eq(assistant.hermesProfile()), anyString()))
            .thenThrow(new IllegalStateException("떼지 못했다"));

        assertCode(() -> creator.create(owner, DEMO, INTRODUCTION, true), ErrorCode.CONNECTOR_OPERATION_FAILED);

        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(owner.id())).as("새 에이전트를 지웠다").isEqualTo(1);
        assertThat(bindingsOf(assistant)).hasSize(1);
        assertThat(bindings.findAll()).as("새 에이전트의 바인딩도 없다").hasSize(1);
    }


    @Test
    @DisplayName("상한, 준비 안 된 연결, 목록 밖 플러그인은 아무것도 만들지 않는다")
    void refusalsBeforeCreatingLeaveNothing() {
        CurrentUser owner = member();
        assertCode(() -> creator.create(owner, DEMO, INTRODUCTION, true), ErrorCode.CONNECTOR_NOT_CONNECTED);
        assertCode(() -> creator.create(owner, "demo-missing", INTRODUCTION, true), ErrorCode.CONNECTOR_NOT_FOUND);
        connect(owner, DEMO);
        for (int i = 0; i < 5; i++) {
            plainAgent(owner, "에이전트 " + i);
        }

        assertCode(() -> creator.create(owner, DEMO, INTRODUCTION, true), ErrorCode.AGENT_LIMIT_REACHED);

        verifyNoInteractions(dashboard);
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(owner.id())).isEqualTo(5);
    }


    private void connect(CurrentUser owner, String connectorId) {
        connectionService.register(owner, connectorId, VALUES);
    }


    private List<ConnectorBinding> bindingsOf(Agent agent) {
        return bindings.findByAgentId(agent.id());
    }


    /** 사람이 등록한 profile 을 쓰는 에이전트다. 지우기가 profile 을 거두지 않는다. */
    private Agent plainAgent(CurrentUser owner, String name) {
        String code = "sp-" + UUID.randomUUID().toString().substring(0, 13);
        return agents.save(Agent.of(
            code,
            name,
            "profile-" + code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
    }


    /** 마법사가 만든 profile({@code ua-} 로 시작한다)에 붙이는 설치만 대시보드가 거절한다. */
    private void rejectBindingToNewProfiles() {
        when(connector.bindConnector(
            argThat(profile -> profile != null && profile.startsWith("ua-")),
            anyString(),
            anyString(),
            anyString(),
            nullable(String.class)))
                .thenThrow(new ConnectorProfileRejected());
    }


    private CurrentUser member() {
        String suffix = UUID.randomUUID().toString();
        AppUser saved = users.save(AppUser.of("specialist-" + suffix + "@example.com", suffix, 1L, UserRole.MEMBER));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }


    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> result) {
        List<Map<String, Object>> content = (List<Map<String, Object>>)result.get("content");
        return (String)content.get(0).get("text");
    }


    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
