package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.application.port.HermesSkillClient;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 「이 에이전트에 붙이기」 승인 카드의 계약을 확인한다(스펙 service-concepts REQ15, REQ16, REQ17, REQ18). 계약은
 * {@code docs/features/service-concepts/design.md} 의 「PR4 연결 붙이기 승인 카드」 가 갖는다.
 *
 * <p>테스트 클래스에 트랜잭션을 두지 않는다. 승인한 붙이기는 승인 트랜잭션 밖에서 돌아야 한다. 대시보드의 커넥터, 스킬, 도구 목록
 * 경로만 대역이다.
 */
@BackendIntegrationTest
class McpConnectorAttachToolTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PROFILE = "mcp-connector-attach";
    private static final String TOOL = "connector_attach";
    private static final String DEMO = "attach-notes";
    private static final String TITLE_OF_CHAT = "붙이기 대화";
    private static final ConnectorManifest MANIFEST = new ConnectorManifest(
        DEMO,
        "검사용 메모",
        "",
        List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null)),
        "list_scopes",
        "attachdemo",
        2,
        List.of(
            new ConnectorTool("list_scopes", "READ", "none", null, null),
            new ConnectorTool("write_note", "WRITE", "required", null, null),
            new ConnectorTool("edit_note", "WRITE", "required", null, null)),
        List.of());

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ConnectorActionService actionService;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    ConnectorConnectionService connectionService;

    @Autowired
    ConnectorBindingService bindingService;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    HermesSkillClient skills;

    @Autowired
    HermesToolsetClient toolsets;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private Agent agent;
    private String token;
    private String root;
    private Conversation conversation;
    private AgentExecution run;


    @BeforeEach
    void setUp() {
        clean();
        when(connector.readCatalog()).thenReturn(List.of(MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap(), nullable(String.class)))
            .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenReturn(new InstallResult(false, false, true));
        when(connector.unbindConnector(anyString(), anyString())).thenReturn(new InstallResult(false, false));
        when(connector.readConnector(anyString(), anyString()))
            .thenReturn(new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(skills.list(anyString())).thenReturn(List.of());
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web"));
        dad = users.save(AppUser.of("attach-dad-" + UUID.randomUUID() + "@example.com", "아빠", 4472L, UserRole.MEMBER));
        token = tokens.issue(PROFILE, "connector-attach").rawToken();
        agent = agents.save(Agent.of(
            "attach-agent", "메모 비서", PROFILE, "http://runtime.test/p/" + PROFILE, CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, dad.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), TITLE_OF_CHAT, agent.id(), Instant.now()));
        root = McpCallSigner.newRoot();
        run = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, root);
    }


    @AfterEach
    void tearDown() {
        clean();
    }


    @Test
    @DisplayName("붙이기 도구는 붙이지 않고 서비스와 도구 수가 보이는 승인 카드를 띄우며, 승인하면 그 에이전트에 붙고 언제 쓸 수 있는지 알림 줄로 알린다")
    void attachesAfterApproval() throws Exception {
        connect();

        JsonNode result = call(DEMO);

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(text(result)).contains("approval_requested", "아직 붙이지 않았다").doesNotContain("검사용 메모");
        assertThat(bindings.findAll()).isEmpty();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        ConnectorAction card = actions.findAll().getFirst();
        assertThat(ControlPlaneActions.owns(card)).isTrue();
        assertThat(card.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(card.conversationId()).isEqualTo(conversation.id());
        var view = actionService.listForConversation(current(dad), conversation.id()).getFirst();
        assertThat(view.title()).isEqualTo("이 에이전트에 붙이기");
        assertThat(view.argsJson()).contains("검사용 메모", "메모 비서", "도구 3개 · 조회 1 · 쓰기 2", "이 서비스의 도구를 직접 써요")
            .doesNotContain("켜져 있어요");
        assertThat(view.grantAllowed()).isFalse();

        var approved = actionService.approve(current(dad), card.publicId(), null, false);

        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(bindingService.listForAgent(current(dad), agent.code()).connections())
            .singleElement()
            .satisfies(bound -> {
                assertThat(bound.connectorId()).isEqualTo(DEMO);
                assertThat(bound.bound()).isTrue();
                assertThat(bound.status()).isEqualTo(BindingStatus.PENDING);
            });
        assertThat(actionService.undeliveredResults(conversation.id())).isEmpty();
        assertThat(notices()).containsExactly("「검사용 메모」 를 「메모 비서」 에 붙였어요. 몇 분 안에 쓸 수 있어요");

        JsonNode again = call(DEMO);
        assertThat(isError(again)).as(text(again)).isFalse();
        assertThat(text(again)).contains("already_attached", "pending");
        assertThat(actions.findAll()).hasSize(1);
    }


    @Test
    @DisplayName("재시작이 필요한 붙이기는 관리자 확인 뒤에 쓸 수 있다고 알리고, 셸 도구가 켜진 에이전트의 카드에는 경고가 더 보인다")
    void tellsRestartAndWarnsShellTools() throws Exception {
        connect();
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenReturn(new InstallResult(true, false));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "terminal"));

        call(DEMO);

        var view = actionService.listForConversation(current(dad), conversation.id()).getFirst();
        assertThat(view.argsJson()).contains("명령 실행이나 파일 도구가 켜져 있어요");
        actionService.approve(current(dad), actions.findAll().getFirst().publicId(), null, false);
        assertThat(notices()).containsExactly("「검사용 메모」 를 「메모 비서」 에 붙였어요. 관리자가 반영을 확인하면 쓸 수 있어요");
    }


    @Test
    @DisplayName("거절하면 붙지 않고, 설치 호출이 실패하면 실패로 끝나며 상태를 확인하라고 알린다")
    void keepsUnboundWhenRejectedAndReportsInstallFailure() throws Exception {
        connect();
        call(DEMO);
        actionService.reject(current(dad), actions.findAll().getFirst().publicId());
        assertThat(bindings.findAll()).isEmpty();

        root = McpCallSigner.newRoot();
        run = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, root);
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenThrow(new IllegalStateException("dashboard down"));
        assertThat(isError(call(DEMO))).isFalse();
        ConnectorAction card = actions.findAll().stream()
            .filter(action -> action.status() == ActionStatus.PENDING).findFirst().orElseThrow();

        var approved = actionService.approve(current(dad), card.publicId(), null, false);

        assertThat(approved.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(notices()).last().asString().contains("반영을 확인하지 못했어요", "외부 서비스 연결 화면");
    }


    @Test
    @DisplayName("그룹에 공개한 에이전트와 남의 에이전트에서는 카드 없이 까닭과 연결 화면 링크를 돌려준다")
    void refusesGroupAndOthersAgent() throws Exception {
        connect();
        jdbc.update("UPDATE agent SET visibility = 'GROUP' WHERE id = ?", agent.id());

        JsonNode group = call(DEMO);

        assertThat(isError(group)).isTrue();
        assertThat(text(group)).contains("agent_not_private", "비공개 에이전트에만", "\"link\":\"/connections/" + DEMO + "\"");

        AppUser other = users.save(AppUser.of("attach-other-" + UUID.randomUUID() + "@example.com", "엄마", 4472L, UserRole.MEMBER));
        jdbc.update("UPDATE agent SET visibility = 'PRIVATE', owner_user_id = ? WHERE id = ?", other.id(), agent.id());

        JsonNode others = call(DEMO);

        assertThat(isError(others)).isTrue();
        assertThat(text(others)).contains("not_owner", "\"link\":\"/connections/" + DEMO + "\"");
        assertThat(actions.findAll()).isEmpty();
        assertThat(bindings.findAll()).isEmpty();
    }


    @Test
    @DisplayName("연결이 준비되지 않았으면 카드 없이 그 플러그인의 연결 화면 링크를 주고, 없는 플러그인은 목록 화면 링크를 준다")
    void pointsToConnectionScreenWhenNotConnected() throws Exception {
        JsonNode notConnected = call(DEMO);

        assertThat(isError(notConnected)).isTrue();
        assertThat(text(notConnected)).contains("not_connected", "\"link\":\"/connections/" + DEMO + "?agent=" + agent.code() + "\"");

        JsonNode missing = call("no-such-plugin");

        assertThat(isError(missing)).isTrue();
        assertThat(text(missing)).contains("not_found", "\"link\":\"/connections\"");
        assertThat(actions.findAll()).isEmpty();
        assertThat(send(json.createObjectNode()).has("error")).isTrue();
        assertThat(send(json.createObjectNode().put("connector", 7)).has("error")).isTrue();
        assertThat(send(json.createObjectNode().put("connector", DEMO).put("agent", "other")).has("error")).isTrue();
    }


    @Test
    @DisplayName("다른 사용자가 연결한 서비스는 내 연결이 아니라서 카드 없이 연결 화면 링크를 준다")
    void othersConnectionIsNotMine() throws Exception {
        AppUser other = users.save(AppUser.of("attach-mom-" + UUID.randomUUID() + "@example.com", "엄마", 4472L, UserRole.MEMBER));
        connectionService.register(current(other), DEMO, Map.of("token", "demo_ok_0123456789"));

        JsonNode result = call(DEMO);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).contains("not_connected");
        assertThat(actions.findAll()).isEmpty();
    }


    @Test
    @DisplayName("붙이는 도중 끊겨 실행 중으로 남은 줄은 다시 실행하지 않고 결과를 확인하지 못했다는 알림 줄을 남긴다")
    void interruptedAttachLeavesUnknownNotice() throws Exception {
        connect();
        call(DEMO);
        ConnectorAction card = actions.findAll().getFirst();
        jdbc.update("UPDATE connector_action SET status = 'EXECUTING', decided_at = ? WHERE id = ?", Timestamp.from(Instant.now()), card.id());

        assertThat(actionService.markInterrupted(Instant.now())).isEqualTo(1);

        assertThat(actions.findById(card.id()).orElseThrow().status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(bindings.findAll()).isEmpty();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        assertThat(notices()).containsExactly("「이 에이전트에 붙이기」 요청의 결과를 확인하지 못했어요. 그 화면에서 상태를 확인해 주세요");
    }


    @Test
    @DisplayName("카드를 띄운 뒤 에이전트를 그룹에 공개하면 승인해도 붙지 않고 실패로 끝난다")
    void rechecksVisibilityAtApproval() throws Exception {
        connect();
        call(DEMO);
        ConnectorAction card = actions.findAll().getFirst();
        jdbc.update("UPDATE agent SET visibility = 'GROUP' WHERE id = ?", agent.id());

        var approved = actionService.approve(current(dad), card.publicId(), null, false);

        assertThat(approved.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(bindings.findAll()).isEmpty();
        assertThat(notices()).containsExactly("「검사용 메모」 를 「메모 비서」 에 붙이지 못했어요. 비공개 에이전트에만 붙일 수 있어요");
    }


    @Test
    @DisplayName("쓰기를 켜지 않은 먼저 살펴보기에서도 붙이기 카드는 띄우고 붙이지는 않는다")
    void checkTreeRaisesCardWithoutWrites() throws Exception {
        connect();
        ProactiveCheck check = ProactiveCheck.started(dad.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, false, Instant.now());
        check.attachRoot(run.id(), root);
        ProactiveCheck saved = checks.save(check);
        try {
            JsonNode result = call(DEMO);

            assertThat(isError(result)).as(text(result)).isFalse();
            assertThat(actions.findAll()).singleElement()
                .satisfies(card -> assertThat(card.status()).isEqualTo(ActionStatus.PENDING));
            assertThat(bindings.findAll()).isEmpty();
        } finally {
            checks.delete(saved);
        }
    }


    private void connect() {
        connectionService.register(current(dad), DEMO, Map.of("token", "demo_ok_0123456789"));
    }


    private List<String> notices() {
        return messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
            .filter(message -> message.role() == MessageRole.SYSTEM)
            .map(message -> message.content())
            .toList();
    }


    private JsonNode call(String connectorId) throws Exception {
        return send(json.createObjectNode().put("connector", connectorId));
    }


    private JsonNode send(ObjectNode arguments) throws Exception {
        ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", TOOL).set("arguments", arguments);
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(McpCallSigner.withContext(request.toString(), token, root)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        if (body.has("result")) {
            McpToolResultShape.assertCallToolResult(TOOL, body.path("result"));
        }
        return body;
    }


    private void clean() {
        actions.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        jdbc.update("DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE title = ?)", TITLE_OF_CHAT);
        jdbc.update("DELETE FROM conversation WHERE title = ?", TITLE_OF_CHAT);
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("DELETE FROM agent WHERE hermes_profile = ?", PROFILE);
    }


    private static CurrentUser current(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private static boolean isError(JsonNode result) {
        return result.path("result").path("isError").asBoolean();
    }


    private static String text(JsonNode result) {
        return result.path("result").path("content").get(0).path("text").asString();
    }
}
