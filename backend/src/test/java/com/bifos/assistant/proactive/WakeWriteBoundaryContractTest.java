package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.ConnectorCatalogTimes;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 매일 깨우기 트리 안에서 쓰기 도구가 승인 없이 열리지 않는다는 계약이다(ADR-20261101 / proactive-suggestion).
 *
 * <p>깨우기는 읽기만 하며 살펴본다. 실제 쓰기는 승인 카드만 거친다. 그래서 살펴보기 줄의 쓰기 허용 값이 무엇이든 커넥터의 쓰기 도구는 {@code allow}
 * 가 되지 않고, Control Plane 의 기억 쓰기 도구는 받지 않는다. 서명한 HTTP 경계로 부른다. 모든 값은 합성이다.
 */
@BackendIntegrationTest
class WakeWriteBoundaryContractTest {
    private static final String PROFILE = "wake-write-boundary";
    private static final String CONNECTOR = "wake-demo-notes";
    private static final String ARGS = "{\"text\":\"합성 메모\"}";

    /** MCP 서버 이름이 {@code wakedemo} 라 등록 이름은 {@code mcp__wakedemo__<도구>} 다. */
    private static final ConnectorManifest MANIFEST = new ConnectorManifest(
        CONNECTOR,
        "검사용 메모",
        "",
        List.of(),
        "list_notes",
        "wakedemo",
        2,
        List.of(
            new ConnectorTool("list_notes", "READ", "none", null, null),
            new ConnectorTool("write_note", "WRITE", "required", "메모 쓰기", null)));

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    McpToolService tools;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser owner;
    private Agent agent;
    private String token;
    private String root;
    private Conversation conversation;
    private ProactiveCheck check;


    @BeforeEach
    void setUp() {
        ConnectorCatalogTimes.expire();
        when(connector.readCatalog()).thenReturn(List.of(MANIFEST));
        owner = users.save(AppUser.of("wake-write-" + UUID.randomUUID() + "@example.com", "깨우기", 1L, UserRole.MEMBER));
        agent = McpCallSigner.agentFor(agents, PROFILE);
        token = tokens.issue(PROFILE, "wake").rawToken();
        conversation = conversations.save(Conversation.startedForCheck(owner.id(), "점검 대화", agent.id(), Instant.now()));
        root = "fos-" + UUID.randomUUID();
        McpCallSigner.running(executions, agents, owner.id(), conversation.id(), PROFILE, root);
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), CONNECTOR, Instant.now());
        connection.ready(Instant.now());
        connection = connections.save(connection);
        ConnectorBinding binding = ConnectorBinding.pending(agent, connection, "wakedemo", Instant.now());
        binding.ready(Instant.now());
        bindings.save(binding);
    }


    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM connector_action WHERE user_id = ?", owner.id());
        jdbc.update("DELETE FROM notification WHERE user_id = ?", owner.id());
        jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", owner.id());
        jdbc.update("DELETE FROM agent_connector_binding WHERE agent_id = ?", agent.id());
        jdbc.update("DELETE FROM connector_connection WHERE user_id = ?", owner.id());
        jdbc.update("DELETE FROM proactive_check WHERE user_id = ?", owner.id());
        jdbc.update("DELETE FROM memory WHERE owner_user_id = ?", owner.id());
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("DELETE FROM conversation WHERE user_id = ?", owner.id());
        jdbc.update("DELETE FROM agent_token WHERE profile_name = ?", PROFILE);
        users.deleteById(owner.id());
    }


    @ParameterizedTest(name = "쓰기 허용 {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("깨우기 트리에서 커넥터 쓰기 도구는 쓰기 허용과 관계없이 allow 가 되지 않고 읽기 도구만 allow 다")
    void connectorWriteNeverPassesInWakeTree(boolean writesAllowed) throws Exception {
        startWake(writesAllowed);

        JsonNode write = policy("mcp__wakedemo__write_note", "write_note");
        JsonNode read = policy("mcp__wakedemo__list_notes", "list_notes");

        assertThat(write.path("decision").asString()).isEqualTo("block");
        assertThat(read.path("decision").asString()).isEqualTo("allow");
        List<Map<String, Object>> writeRows = jdbc.queryForList(
            "SELECT decision, passed FROM connector_action WHERE user_id = ? AND tool_name = 'write_note'", owner.id());
        assertThat(writeRows).hasSize(1);
        assertThat(writeRows.getFirst().get("PASSED")).as("쓰기 줄은 실행으로 넘어가지 않는다").isEqualTo(false);
        assertThat(writeRows.getFirst().get("DECISION"))
            .as("읽기 전용이면 막고, 쓰기를 허용했으면 승인 카드로만 보낸다")
            .isEqualTo(writesAllowed ? "NEEDS_APPROVAL" : "DENIED");
    }


    @ParameterizedTest(name = "쓰기 허용 {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("깨우기 트리에서 memory_remember 는 쓰기 허용과 관계없이 받지 않고 기억을 남기지 않는다")
    void memoryWriteIsRefusedInWakeTree(boolean writesAllowed) throws Exception {
        startWake(writesAllowed);

        ObjectNode arguments = json.createObjectNode().put("title", "합성 제목").put("content", "합성 본문");
        arguments.set("_fos_ctx", McpCallSigner.context(token, "memory_remember", root));
        JsonNode result = mcp("memory_remember", arguments).path("result");

        assertThat(result).isEqualTo(json.valueToTree(tools.notAllowedInCheck()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory WHERE owner_user_id = ?", Long.class, owner.id()))
            .isZero();
    }


    /** 이 실행을 루트로 둔 매일 깨우기 살펴보기 줄을 남긴다. */
    private void startWake(boolean writesAllowed) {
        AgentExecution run = executions.findAll().stream()
            .filter(execution -> root.equals(execution.hermesSessionId()))
            .findFirst()
            .orElseThrow();
        check = ProactiveCheck.started(
            owner.id(), agent.id(), conversation.id(), CheckTrigger.SCHEDULED, writesAllowed, Instant.now());
        check.attachRoot(run.id(), root);
        check = checks.save(check);
    }


    private JsonNode policy(String hermesTool, String tool) throws Exception {
        String body = McpCallSigner
            .policyBody(token, hermesTool, tool, root, root, "call_" + UUID.randomUUID(), ARGS)
            .toString();
        HttpResponse<String> response = post("/internal/hermes/connector-policy", body);
        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }


    private JsonNode mcp(String tool, ObjectNode arguments) throws Exception {
        String body = json.writeValueAsString(Map.of(
            "jsonrpc",
            "2.0",
            "id",
            1,
            "method",
            "tools/call",
            "params",
            Map.of("name", tool, "arguments", arguments)));
        HttpResponse<String> response = post("/mcp", body);
        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }


    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
