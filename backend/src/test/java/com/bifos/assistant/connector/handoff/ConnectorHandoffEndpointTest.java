package com.bifos.assistant.connector.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.application.BrowserHumanHold;
import com.bifos.assistant.browser.application.BrowserLeftTabs;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.action.domain.ConnectorActionKey;
import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import com.bifos.assistant.connector.handoff.application.ConnectorHandoffs;
import com.bifos.assistant.connector.handoff.application.dto.HandoffCloseOutcome;
import com.bifos.assistant.connector.handoff.application.dto.HandoffCloseRequest;
import com.bifos.assistant.connector.handoff.application.dto.HandoffCloseResult;
import com.bifos.assistant.connector.handoff.application.dto.HandoffView;
import com.bifos.assistant.connector.handoff.presentation.ConnectorHandoffController;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.orchestration.application.dto.PluginCallEnded;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.ConnectorCatalogTimes;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 화면 넘기기 알림의 인증과 판정 줄 대조, 한 번만 적기, 알림, 대화의 카드 목록을 보고, 카드의 단추와 같은 플러그인의 다음
 * 호출로 닫기를 본다(docs/features/mobile-default-chat/design.md).
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
    ConnectorHandoffController controller;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BrowserHumanHold holds;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    BrowserLeftTabs tabs;

    @Autowired
    TestClock clock;

    @Autowired
    ChatMessageRepository messages;

    private final HttpClient client = HttpClient.newHttpClient();
    private String token;
    private AppUser owner;
    private Agent agent;
    private Conversation conversation;
    private Conversation other;


    @BeforeEach
    void setUp() {
        cleanup();
        clock.set(NOW);
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
        SecurityContextHolder.clearContext();
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
        UUID actionId = handoffs.open(user(), conversation.id()).getFirst().handoffId();
        assertThat(notifications.findAll()).singleElement().satisfies(notification -> {
            assertThat(notification.targetType()).isEqualTo(NotificationTargetType.CONVERSATION);
            assertThat(notification.targetPublicId()).isEqualTo(conversation.publicId());
            assertThat(notification.sourcePublicId()).isEqualTo(actionId);
        });

        List<HandoffView> cards = handoffs.open(user(), conversation.id());
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.need()).isEqualTo(HandoffNeed.LOGIN);
            assertThat(card.tab()).isEqualTo("TAB1");
            assertThat(card.connectorTitle()).isEqualTo("네이버 쇼핑");
        });
        assertThat(handoffs.open(user(), other.id())).isEmpty();
    }


    @Test
    @DisplayName("봇 탐지와 예상 밖 화면 넘기기도 적고, 알림 제목은 어휘마다 정해 둔 글이다")
    void recordsBotCheckAndUnexpectedPage() throws Exception {
        insertAction("root-10", "call-10", true, conversation.id());
        insertAction("root-11", "call-11", true, other.id());

        assertThat(post(McpCallSigner.handoffBody(
            token, TOOL, "root-10", "root-10", "call-10", "DEMO_BOT_CHECK", "bot_check", "TAB1")).statusCode())
                .isEqualTo(200);
        assertThat(post(McpCallSigner.handoffBody(
            token, TOOL, "root-11", "root-11", "call-11", "DEMO_PAGE_CHANGED", "unexpected_page", "TAB2")).statusCode())
                .isEqualTo(200);

        assertThat(jdbc.queryForList("SELECT handoff_need FROM connector_action ORDER BY id", String.class))
            .containsExactly("BOT_CHECK", "UNEXPECTED_PAGE");
        assertThat(jdbc.queryForList("SELECT title FROM notification ORDER BY id", String.class))
            .containsExactly("「내 브라우저」에서 자동 접속 확인을 풀어 주세요", "「내 브라우저」에서 화면을 확인해 주세요");
        assertThat(handoffs.open(user(), conversation.id())).extracting(HandoffView::need).containsExactly(HandoffNeed.BOT_CHECK);
        assertThat(handoffs.open(user(), other.id())).extracting(HandoffView::need).containsExactly(HandoffNeed.UNEXPECTED_PAGE);
    }


    @Test
    @DisplayName("탭 없는 human_browser 를 적고, 그 대화의 패널에서 넘겨받았으면 알림 없이 카드만 내고 다른 대화면 알린다")
    void recordsTablessHumanBrowserAndSkipsNoticeForWatchedConversation() throws Exception {
        jdbc.update("INSERT INTO user_browser (user_id, status, profile_key, last_active_at, started_at, created_at, updated_at, version)"
            + " VALUES (?, 'RUNNING', ?, ?, ?, ?, ?, 0)", owner.id(), "a".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW),
            Timestamp.from(NOW), Timestamp.from(NOW));
        holds.takeOver(owner.id(), conversation.publicId().toString());
        insertAction("root-20", "call-20", true, conversation.id());
        insertAction("root-21", "call-21", true, other.id());

        assertThat(post(McpCallSigner.handoffBody(
            token, TOOL, "root-20", "root-20", "call-20", "DEMO_BROWSER_IN_USE", "human_browser", null)).statusCode())
                .isEqualTo(200);
        assertThat(post(McpCallSigner.handoffBody(
            token, TOOL, "root-21", "root-21", "call-21", "DEMO_BROWSER_IN_USE", "human_browser", null)).statusCode())
                .isEqualTo(200);

        assertThat(handoffs.open(user(), conversation.id())).singleElement().satisfies(card -> {
            assertThat(card.need()).isEqualTo(HandoffNeed.HUMAN_BROWSER);
            assertThat(card.tab()).isNull();
        });
        assertThat(notifications.findAll()).singleElement()
            .satisfies(notification -> assertThat(notification.targetPublicId()).isEqualTo(other.publicId()));
        holds.giveBack(owner.id());
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
    @DisplayName("대화의 카드 목록은 주인에게만 보이고, 다른 사용자가 부르면 없는 대화와 같은 오류다")
    void listsCardsOnlyToOwner() throws Exception {
        insertAction("root-7", "call-7", true, conversation.id());
        post(McpCallSigner.handoffBody(token, TOOL, "root-7", "root-7", "call-7", CODE, "login", "TAB1"));
        AppUser stranger = users.save(AppUser.of("handoff-stranger@example.com", "남", 1L, UserRole.MEMBER));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user(), null));
        assertThat(controller.open(conversation.publicId())).singleElement()
            .satisfies(card -> assertThat(card.tab()).isEqualTo("TAB1"));

        CurrentUser other = new CurrentUser(stranger.id(), stranger.email(), "남", 1L, UserRole.MEMBER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(other, null));
        assertThatThrownBy(() -> controller.open(conversation.publicId()))
            .isInstanceOfSatisfying(
                ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
    }


    @Test
    @DisplayName("같은 플러그인의 다음 호출이 넘기기 없이 끝나면 그 카드만 닫고 알림을 읽음으로 바꾸며, 실패와 다른 플러그인의 성공은 닫지 않는다")
    void closesOnSamePluginSuccessOnly() throws Exception {
        insertAction("root-5", "call-5", true, conversation.id());
        insertAction("root-6", "call-6", true, other.id());
        post(McpCallSigner.handoffBody(token, TOOL, "root-5", "root-5", "call-5", CODE, "login", "TAB1"));
        post(McpCallSigner.handoffBody(token, TOOL, "root-6", "root-6", "call-6", CODE, "device_check", "TAB2"));
        insertAction("root-5", "call-7", true, conversation.id());
        insertAction("root-5", "call-8", true, conversation.id(), "calendar");

        ended("root-5", "call-7", true);
        ended("root-5", "call-8", false);
        ended("root-5", "call-none", false);
        assertThat(handoffs.open(user(), conversation.id())).as("실패, 다른 플러그인, 판정 줄 없는 도구").hasSize(1);

        ended("root-5", "call-5", false);
        assertThat(handoffs.open(user(), conversation.id())).as("넘기기를 낸 그 호출의 기록").hasSize(1);

        insertAction("root-5", "call-9", true, conversation.id());
        ended("root-5", "call-9", false);

        assertThat(handoffs.open(user(), conversation.id())).isEmpty();
        verify(tabs).close(eq(owner.id()), eq("TAB1"), any());
        assertThat(handoffs.open(user(), other.id())).singleElement()
            .satisfies(card -> assertThat(card.need()).isEqualTo(HandoffNeed.DEVICE_CHECK));
        assertThat(jdbc.queryForList("SELECT read_at FROM notification ORDER BY id"))
            .extracting(row -> row.get("READ_AT") != null)
            .containsExactly(true, false);
    }


    @Test
    @DisplayName("승인한 주문서 채우기가 기다리는 줄의 넘기기는 같은 플러그인의 다른 호출이 성공해도 닫지 않는다")
    void keepsWaitingApprovalHandoffOnPluginSuccess() throws Exception {
        insertAction("root-20", "call-20", true, conversation.id());
        post(McpCallSigner.handoffBody(token, TOOL, "root-20", "root-20", "call-20", CODE, "login", "TAB1"));
        jdbc.update("UPDATE connector_action SET status = 'WAITING' WHERE dedupe_key = ?",
            ConnectorActionKey.of(PROFILE, "root-20", "root-20", "call-20").value());
        insertAction("root-20", "call-21", true, conversation.id());

        ended("root-20", "call-21", false);

        assertThat(handoffs.open(user(), conversation.id())).hasSize(1);
    }


    @Test
    @DisplayName("같은 플러그인의 새 넘기기는 앞선 카드를 닫고, 도구 기록이 넘기기 알림보다 먼저 와도 새 카드 하나만 남는다")
    void newHandoffSupersedesEarlierInEitherOrder() throws Exception {
        insertAction("root-12", "call-12", true, conversation.id());
        post(McpCallSigner.handoffBody(token, TOOL, "root-12", "root-12", "call-12", CODE, "login", "TAB1"));
        insertAction("root-12", "call-13", true, conversation.id());
        post(McpCallSigner.handoffBody(token, TOOL, "root-12", "root-12", "call-13", CODE, "captcha", "TAB1"));
        assertThat(handoffs.open(user(), conversation.id())).singleElement()
            .satisfies(card -> assertThat(card.need()).isEqualTo(HandoffNeed.CAPTCHA));

        insertAction("root-12", "call-14", true, conversation.id());
        ended("root-12", "call-14", false);
        post(McpCallSigner.handoffBody(token, TOOL, "root-12", "root-12", "call-14", CODE, "device_check", "TAB1"));

        assertThat(handoffs.open(user(), conversation.id())).singleElement()
            .satisfies(card -> assertThat(card.need()).isEqualTo(HandoffNeed.DEVICE_CHECK));
    }


    @Test
    @DisplayName("「다 했어요」 는 그 카드 하나만 닫고 처음 한 번만 이어 가라고 답하며, 「그만할래요」 는 그만뒀다고 답한다")
    void buttonClosesOnlyThatCardOnce() throws Exception {
        connected();
        doReturn(false).when(tabs).gone(anyLong(), anyString(), any());
        UUID first = handoffOf("root-15", "call-15", "TAB1", "naver-shopping");
        UUID second = handoffOf("root-16", "call-16", "TAB2", "korail-ktx");
        asOwner();

        assertThat(controller.close(conversation.publicId(), first, new HandoffCloseRequest(HandoffCloseOutcome.DONE)).result())
            .isEqualTo(HandoffCloseResult.CONTINUE);
        assertThat(controller.close(conversation.publicId(), first, new HandoffCloseRequest(HandoffCloseOutcome.DONE)).result())
            .as("두 번 누름").isEqualTo(HandoffCloseResult.ALREADY_CLOSED);
        assertThat(handoffs.open(user(), conversation.id())).singleElement()
            .satisfies(card -> assertThat(card.handoffId()).isEqualTo(second));

        assertThat(controller.close(conversation.publicId(), second, new HandoffCloseRequest(HandoffCloseOutcome.STOP)).result())
            .isEqualTo(HandoffCloseResult.STOPPED);
        assertThat(handoffs.open(user(), conversation.id())).isEmpty();
        assertThat(jdbc.queryForList("SELECT read_at FROM notification"))
            .extracting(row -> row.get("READ_AT") != null)
            .containsExactly(true, true);
        assertThat(systemLines()).isEmpty();
        verify(tabs, never()).close(anyLong(), anyString(), any());
    }


    @Test
    @DisplayName("남긴 탭이 없어졌거나 연결이 끊겼으면 카드를 닫고 이어 가지 않으며 대화에 알림 줄을 남긴다")
    void doneWithoutTabOrConnectionIsUnavailable() throws Exception {
        doReturn(true).when(tabs).gone(anyLong(), anyString(), any());
        UUID noConnection = handoffOf("root-17", "call-17", "TAB1", "naver-shopping");
        asOwner();

        assertThat(controller.close(conversation.publicId(), noConnection, new HandoffCloseRequest(HandoffCloseOutcome.DONE)).result())
            .isEqualTo(HandoffCloseResult.UNAVAILABLE);

        connected();
        UUID noTab = handoffOf("root-18", "call-18", "TAB1", "naver-shopping");
        assertThat(controller.close(conversation.publicId(), noTab, new HandoffCloseRequest(HandoffCloseOutcome.DONE)).result())
            .isEqualTo(HandoffCloseResult.UNAVAILABLE);

        assertThat(handoffs.open(user(), conversation.id())).isEmpty();
        assertThat(systemLines()).containsExactly(
            "연결이 끊겨 이어 갈 수 없어요. 「외부 서비스 연결」을 확인한 뒤 다시 맡겨 주세요.", "화면이 닫혀 이어 갈 수 없어요. 다시 맡겨 주세요.");
    }


    @Test
    @DisplayName("다른 대화의 넘기기를 이 대화 주소로 닫으면 없는 대화와 같은 오류이고 카드는 그대로다")
    void rejectsHandoffOfOtherConversation() throws Exception {
        insertAction("root-19", "call-19", true, other.id());
        post(McpCallSigner.handoffBody(token, TOOL, "root-19", "root-19", "call-19", CODE, "login", "TAB1"));
        UUID foreign = handoffs.open(user(), other.id()).getFirst().handoffId();
        asOwner();

        assertThatThrownBy(() -> controller.close(conversation.publicId(), foreign, new HandoffCloseRequest(HandoffCloseOutcome.DONE)))
            .isInstanceOfSatisfying(
                ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
        assertThat(handoffs.open(user(), other.id())).hasSize(1);
    }


    /** 판정 줄을 넣고 넘기기를 알린 뒤 그 카드의 번호를 돌려준다. */
    private UUID handoffOf(String root, String call, String tab, String connectorId) throws Exception {
        insertAction(root, call, true, conversation.id(), connectorId);
        post(McpCallSigner.handoffBody(token, TOOL, root, root, call, CODE, "login", tab));
        return jdbc.queryForObject("SELECT public_id FROM connector_action WHERE dedupe_key = ?",
            (rs, row) -> {
                ByteBuffer bytes = ByteBuffer.wrap(rs.getBytes(1));
                return new UUID(bytes.getLong(), bytes.getLong());
            },
            ConnectorActionKey.of(PROFILE, root, root, call).value());
    }


    /** fos-ctx 의 도구 기록이 알린 호출의 끝이다. 최상위 run 이라 루트와 session 이 같다. */
    private void ended(String root, String call, boolean failed) {
        events.publishEvent(new PluginCallEnded(PROFILE, root, root, call, failed));
    }


    private void connected() {
        jdbc.update("INSERT INTO connector_connection (user_id, connector_id, status, fields, created_at, updated_at, undeclared_tools,"
            + " vault_stored) VALUES (?, 'naver-shopping', 'READY', '{}', ?, ?, 0, 0)", owner.id(), Timestamp.from(NOW),
            Timestamp.from(NOW));
    }


    private void asOwner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user(), null));
    }


    private List<String> systemLines() {
        return messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
            .filter(message -> message.role() == MessageRole.SYSTEM)
            .map(ChatMessage::content)
            .toList();
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


    /** 판정 경로가 남기는 모양의 허용 줄이다. 최상위 run 이라 루트와 session 이 같다. 줄마다 시계를 1초 옮겨 판정 시각이 앞선 넘기기보다 늦다. */
    private void insertAction(String root, String call, boolean passed, Long conversationId) {
        insertAction(root, call, passed, conversationId, "naver-shopping");
    }


    private void insertAction(String root, String call, boolean passed, Long conversationId, String connectorId) {
        clock.advance(Duration.ofSeconds(1));
        UUID publicId = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
            .putLong(publicId.getMostSignificantBits())
            .putLong(publicId.getLeastSignificantBits())
            .array();
        jdbc.update(
            "INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,"
                + " decision, passed, origin_execution_id, conversation_id, dedupe_key, args_sha256, created_at)"
                + " VALUES (?, ?, ?, ?, 'search_products', ?, 'READ', ?, ?, 1, ?, ?, ?, ?)",
            bytes,
            owner.id(),
            agent.id(),
            connectorId,
            TOOL,
            passed ? "ALLOWED" : "DENIED",
            passed,
            conversationId,
            ConnectorActionKey.of(PROFILE, root, root, call).value(),
            "0".repeat(64),
            Timestamp.from(clock.instant()));
    }


    private CurrentUser user() {
        return new CurrentUser(owner.id(), owner.email(), "주인", 1L, UserRole.MEMBER);
    }


    private void cleanup() {
        jdbc.update("DELETE FROM connector_action");
        jdbc.update("DELETE FROM connector_connection WHERE connector_id = 'naver-shopping'");
        jdbc.update("DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE title IN ('쇼핑 대화', '다른 대화'))");
        jdbc.update("DELETE FROM user_browser");
        notifications.deleteAll();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("DELETE FROM conversation WHERE title IN ('쇼핑 대화', '다른 대화')");
        jdbc.update("DELETE FROM app_user WHERE email IN ('handoff-owner@example.com', 'handoff-stranger@example.com')");
    }
}
