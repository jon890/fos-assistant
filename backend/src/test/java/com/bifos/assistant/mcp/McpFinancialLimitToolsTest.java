package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.connector.application.ConnectorFinancialProperties;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverridableLiveProperties;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 주문 상한 도구의 목록과 살펴보기 차단을 본다(ADR-20261015 / financial-user-limits).
 *
 * <p>살펴보기와 매일 깨우기 트리는 상한을 바꾸는 도구를 부르지 못한다. 서명이 없는 호출은 {@link McpCallerInvariantTest} 가 본다.
 */
@BackendIntegrationTest
class McpFinancialLimitToolsTest {
    private static final String PROFILE = "mcp-order-limit-tool";

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
    ProactiveCheckRepository checks;
    @Autowired
    LiveProperties<ConnectorFinancialProperties> financial;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser owner;
    private String token;
    private String root;
    private Conversation conversation;
    private AgentExecution run;


    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        owner = users.save(AppUser.of("mcp-order-limit-" + UUID.randomUUID() + "@example.com", "주인", 1L, UserRole.MEMBER));
        token = tokens.issue(PROFILE, "order-limit").rawToken();
        Agent agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(owner.id(), "상한 검사 대화", agent.id(), Instant.now()));
        root = McpCallSigner.newRoot();
        run = McpCallSigner.running(executions, agents, owner.id(), conversation.id(), PROFILE, root);
        OverridableLiveProperties.during(financial, new ConnectorFinancialProperties(List.of("7:" + owner.email()),
            new BigDecimal("100000"), new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("300000"), new BigDecimal("1000"), ""));
    }


    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE app_user SET limit_order_amount_krw = NULL, limit_order_amount_usd = NULL, limit_order_quantity = NULL, "
            + "limit_daily_amount_krw = NULL, limit_daily_amount_usd = NULL, limit_updated_at = NULL WHERE id = ?", owner.id());
        jdbc.update("DELETE FROM conversation WHERE user_id = ?", owner.id());
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
    }


    @Test
    @DisplayName("도구 목록에 order_limit_read 와 order_limit_change 가 있고, change 는 이름과 값만 받는다")
    void listsBothTools() throws Exception {
        JsonNode tools = body(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).path("result").path("tools");
        var names = StreamSupport.stream(tools.spliterator(), false).map(tool -> tool.path("name").asString()).toList();
        assertThat(names).contains("order_limit_read", "order_limit_change");
        JsonNode change = StreamSupport.stream(tools.spliterator(), false)
            .filter(tool -> "order_limit_change".equals(tool.path("name").asString())).findFirst().orElseThrow();
        assertThat(change.path("inputSchema").path("required").toString()).isEqualTo("[\"name\",\"value\"]");
        assertThat(change.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
    }


    @Test
    @DisplayName("대화의 실행이 부른 낮추기는 바로 반영된다")
    void conversationRunLowersLimit() throws Exception {
        JsonNode result = call("order_limit_change", json.createObjectNode().put("name", "order_amount_usd").put("value", "300"));
        assertThat(result.path("isError").asBoolean()).as("결과: %s", result).isFalse();
        assertThat(result.toString()).contains("applied");
        assertThat(jdbc.queryForObject("select limit_order_amount_usd from app_user where id = ?", BigDecimal.class,
            owner.id())).isEqualByComparingTo("300");
    }


    @ParameterizedTest(name = "쓰기 도구 허용 {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("먼저 살펴보기 트리는 쓰기 허용과 관계없이 상한을 읽거나 바꾸지 못하고 아무것도 저장하지 않는다")
    void checkTreeCannotChangeLimit(boolean writesAllowed) throws Exception {
        ProactiveCheck check = ProactiveCheck.started(owner.id(), 1L, conversation.id(), CheckTrigger.MANUAL, writesAllowed, Instant.now());
        check.attachRoot(run.id(), root);
        ProactiveCheck saved = checks.save(check);
        try {
            JsonNode change = call("order_limit_change", json.createObjectNode().put("name", "order_amount_usd").put("value", "300"));
            assertThat(change.path("isError").asBoolean()).as("결과: %s", change).isTrue();
            JsonNode read = call("order_limit_read", json.createObjectNode());
            assertThat(read.path("isError").asBoolean()).as("결과: %s", read).isTrue();
            assertThat(jdbc.queryForObject("select limit_updated_at from app_user where id = ?", Instant.class, owner.id())).isNull();
        } finally {
            checks.delete(saved);
        }
    }


    private JsonNode call(String tool, ObjectNode arguments) throws Exception {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.set("arguments", arguments);
        return body(send(McpCallSigner.withContext(json.writeValueAsString(request), token, root))).path("result");
    }


    private HttpResponse<String> send(String request) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(request))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }


    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("본문: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
