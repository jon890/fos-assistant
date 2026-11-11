package com.bifos.assistant.connector.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.dto.ConversationTurnStarted;
import com.bifos.assistant.connector.action.domain.ConnectorActionKey;
import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import com.bifos.assistant.connector.handoff.application.ConnectorHandoffs;
import com.bifos.assistant.connector.handoff.application.dto.HandoffView;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.ConnectorCatalogTimes;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 화면 넘기기 알림의 인증과 판정 줄 대조, 한 번만 적기, 알림, 대화의 카드 목록, 다음 turn 에서 닫기를 본다.
 *
 * <p>계약은 {@code docs/adr/ADR-20261111-connector-handoff.md} 의 「결정」 이다. 판정 줄은 판정 경로가 남긴 모양으로 직접 넣는다.
 */
@BackendIntegrationTest
class ConnectorHandoffEndpointTest {
    private static final String PATH = "/internal/hermes/connector-handoff";
    private static final String PROFILE = "connector-handoff-test";
    private static final String TOOL = "mcp__naver_shopping__search_products";
    private static final String CODE = "NAVER_SHOPPING_LOGIN_REQUIRED";
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

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
    NotificationRepository notifications;

    @Autowired
    ConnectorHandoffs handoffs;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HermesConnectorClient connector;

    private final HttpClient client = HttpClient.newHttpClient();
    private String token;
    private AppUser owner;
    private Agent agent;
    private Conversation conversation;
    private Conversation other;


    @BeforeEach
    void setUp() {
        cleanup();
        ConnectorCatalogTimes.expire();
        when(connector.readCatalog()).thenReturn(List.of(new ConnectorManifest(
            "naver-shopping", "네이버 쇼핑", "", List.of(), "check_shopping_login", "naver-shopping", 2,
            List.of(new ConnectorTool("check_shopping_login", "READ", "none", null, null),
                new ConnectorTool("search_products", "READ", "required", null, null)))));
        owner = users.save(AppUser.of("handoff-owner@example.com", "주인", 1L, UserRole.MEMBER));
        agent = McpCallSigner.agentFor(agents, PROFILE);
        token = tokens.issue(PROFILE, "owner").rawToken();
        conversation = conversations.save(Conversation.startedBy(owner.id(), "쇼핑 대화", agent.id(), NOW));
        other = conversations.save(Conversation.startedBy(owner.id(), "다른 대화", agent.id(), NOW));
    }


    @AfterEach
    void tearDown() {
        cleanup();
    }


    @Test
    @DisplayName("통과한 같은 호출의 판정 줄에 넘기기를 한 번만 적고 알림 한 건을 남기며, 대화의 카드 목록에 보인다")
    void recordsOnceAndListsCard() throws Exception {
        insertAction("root-1", "call-1", true, conversation.id());

        assertThat(post(McpCallSigner.handoffBody(token, TOOL, "root-1", "root-1", "call-1", CODE, "login", "TAB1"))
            .statusCode()).isEqualTo(200);
        assertThat(post(McpCallSigner.handoffBody(token, TOOL, "root-1", "root-1", "call-1", CODE, "captcha", "TAB2"))
            .statusCode()).isEqualTo(200);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT handoff_need, handoff_tab, handoff_at, handoff_closed_at FROM connector_action");
        assertThat(row.get("HANDOFF_NEED")).isEqualTo("LOGIN");
        assertThat(row.get("HANDOFF_TAB")).isEqualTo("TAB1");
        assertThat(row.get("HANDOFF_AT")).isNotNull();
        assertThat(row.get("HANDOFF_CLOSED_AT")).isNull();
        List<Map<String, Object>> sent = jdbc.queryForList("SELECT kind, title, body, read_at FROM notification");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("KIND")).isEqualTo("HANDOFF_REQUESTED");
        assertThat(sent.get(0).get("TITLE")).isEqualTo("「내 브라우저」에서 로그인해 주세요");
        assertThat(sent.get(0).get("BODY")).isEqualTo("「네이버 쇼핑」");

        List<HandoffView> cards = handoffs.open(user(), conversation.id());
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.need()).isEqualTo(HandoffNeed.LOGIN);
            assertThat(card.tab()).isEqualTo("TAB1");
            assertThat(card.connectorTitle()).isEqualTo("네이버 쇼핑");
        });
        assertThat(handoffs.open(user(), other.id())).isEmpty();
    }


    @Test
    @DisplayName("판정 줄이 없거나, 통과하지 않았거나, 등록 이름이 다르거나, 서명이 틀리면 거절하고 아무것도 적지 않는다")
    void rejectsUnmatchedCalls() throws Exception {
        insertAction("root-2", "call-2", false, conversation.id());
        insertAction("root-3", "call-3", true, conversation.id());

        HttpResponse<String> missing = post(McpCallSigner.handoffBody(token, TOOL, "root-9", "root-9", "call-9", CODE, "login", "TAB1"));
        HttpResponse<String> denied = post(McpCallSigner.handoffBody(token, TOOL, "root-2", "root-2", "call-2", CODE, "login", "TAB1"));
        HttpResponse<String> otherTool = post(McpCallSigner.handoffBody(
            token, "mcp__naver_shopping__get_product_detail", "root-3", "root-3", "call-3", CODE, "login", "TAB1"));
        ObjectNode forged = McpCallSigner.handoffBody(token, TOOL, "root-3", "root-3", "call-3", CODE, "login", "TAB1");
        forged.put("tab", "TAB9");
        HttpResponse<String> badSig = post(forged);

        for (HttpResponse<String> response : List.of(missing, denied, otherTool, badSig)) {
            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(response.body()).contains("CONNECTOR_POLICY_REJECTED");
        }
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM connector_action WHERE handoff_need IS NOT NULL", Long.class)).isZero();
        assertThat(notifications.count()).isZero();
    }


    @Test
    @DisplayName("profile 토큰이 없으면 거절한다")
    void requiresProfileToken() throws Exception {
        insertAction("root-4", "call-4", true, conversation.id());
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(McpCallSigner
                    .handoffBody(token, TOOL, "root-4", "root-4", "call-4", CODE, "login", "TAB1").toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM connector_action WHERE handoff_need IS NOT NULL", Long.class)).isZero();
    }


    @Test
    @DisplayName("그 대화의 새 turn 이 시작되면 그 대화의 열린 넘기기만 닫고 그 알림을 읽음으로 바꾼다")
    void closesOnNextTurn() throws Exception {
        insertAction("root-5", "call-5", true, conversation.id());
        insertAction("root-6", "call-6", true, other.id());
        post(McpCallSigner.handoffBody(token, TOOL, "root-5", "root-5", "call-5", CODE, "login", "TAB1"));
        post(McpCallSigner.handoffBody(token, TOOL, "root-6", "root-6", "call-6", CODE, "device_check", "TAB2"));

        events.publishEvent(new ConversationTurnStarted(conversation.id()));

        assertThat(handoffs.open(user(), conversation.id())).isEmpty();
        assertThat(handoffs.open(user(), other.id())).singleElement()
            .satisfies(card -> assertThat(card.need()).isEqualTo(HandoffNeed.DEVICE_CHECK));
        assertThat(jdbc.queryForList("SELECT title, read_at FROM notification ORDER BY id"))
            .extracting(row -> row.get("READ_AT") != null)
            .containsExactly(true, false);
    }


    private HttpResponse<String> post(ObjectNode body) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }


    /** 판정 경로가 남기는 모양의 허용 줄이다. 최상위 run 이라 루트와 session 이 같다. */
    private void insertAction(String root, String call, boolean passed, Long conversationId) {
        UUID publicId = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
            .putLong(publicId.getMostSignificantBits())
            .putLong(publicId.getLeastSignificantBits())
            .array();
        jdbc.update(
            "INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,"
                + " decision, passed, origin_execution_id, conversation_id, dedupe_key, args_sha256, created_at)"
                + " VALUES (?, ?, ?, 'naver-shopping', 'search_products', ?, 'READ', ?, ?, 1, ?, ?, ?, ?)",
            bytes,
            owner.id(),
            agent.id(),
            TOOL,
            passed ? "ALLOWED" : "DENIED",
            passed,
            conversationId,
            ConnectorActionKey.of(PROFILE, root, root, call).value(),
            "0".repeat(64),
            Timestamp.from(NOW));
    }


    private CurrentUser user() {
        return new CurrentUser(owner.id(), owner.email(), "주인", 1L, UserRole.MEMBER);
    }


    private void cleanup() {
        jdbc.update("DELETE FROM connector_action");
        notifications.deleteAll();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("DELETE FROM conversation WHERE title IN ('쇼핑 대화', '다른 대화')");
        jdbc.update("DELETE FROM app_user WHERE email = 'handoff-owner@example.com'");
    }
}
