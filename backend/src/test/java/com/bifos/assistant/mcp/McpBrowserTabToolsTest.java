package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.browser.application.BrowserAgentControl;
import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.FakeCdpTargets;
import com.bifos.assistant.testsupport.OverrideProperties;
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
 * 실제 HTTP 경계에서 「내 브라우저」 탭 도구의 계약을 본다(docs/features/agent-browser/design.md 의 「PR1 도구」, 「PR1 먼저 살펴보기와 사람이
 * 없는 실행」). Chrome 의 탭 창구와 컨테이너만 대역이다.
 *
 * <p>테스트 클래스에 트랜잭션을 두지 않는다. 승인한 동작은 승인 트랜잭션 밖에서 돈다.
 */
@BackendIntegrationTest
@OverrideProperties("assistant.browser.enabled=true")
class McpBrowserTabToolsTest {
    private static final String PROFILE = "mcp-browser-tabs";
    private static final String TITLE_OF_CHAT = "브라우저 탭 대화";

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
    ProactiveCheckRepository checks;

    @Autowired
    UserBrowserService browsers;

    @Autowired
    BrowserAgentControl control;

    @Autowired
    FakeCdpTargets cdp;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private AppUser mom;
    private String token;
    private String root;
    private Conversation conversation;
    private AgentExecution run;


    @BeforeEach
    void setUp() {
        clean();
        dad = users.save(AppUser.of("tabs-dad-" + UUID.randomUUID() + "@example.com", "아빠", 4473L, UserRole.MEMBER));
        mom = users.save(AppUser.of("tabs-mom-" + UUID.randomUUID() + "@example.com", "엄마", 4473L, UserRole.MEMBER));
        token = tokens.issue(PROFILE, "browser-tabs").rawToken();
        Agent agent = agents.save(Agent.of(
            "browser-tabs-agent", "탭 비서", PROFILE, "http://runtime.test/p/" + PROFILE, CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, dad.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), TITLE_OF_CHAT, agent.id(), Instant.now()));
        root = McpCallSigner.newRoot();
        run = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, root);
        cdp.pages.add(new CdpTarget("TAB1", "받은 편지함", "https://mail.example.com/inbox"));
        cdp.pages.add(new CdpTarget("TAB2", "오늘의 뉴스", "https://news.example.com/today"));
    }


    @AfterEach
    void tearDown() {
        clean();
    }


    @Test
    @DisplayName("브라우저가 없거나 사용자가 켜지 않았으면 탭을 보이지 않고 까닭과 「내 브라우저」 화면을 돌려준다")
    void refusesUntilUserTurnsItOn() throws Exception {
        JsonNode missing = call("browser_tabs", json.createObjectNode());
        assertThat(isError(missing)).isTrue();
        assertThat(text(missing)).contains("no_browser", "/browser").doesNotContain("받은 편지함");

        browsers.create(dad.id());
        JsonNode off = call("browser_tabs", json.createObjectNode());
        assertThat(isError(off)).isTrue();
        assertThat(text(off)).contains("user_off", "/browser").doesNotContain("받은 편지함");
        assertThat(cdp.closed).isEmpty();
    }


    @Test
    @DisplayName("켜면 켜진 브라우저의 탭을 보고, 꺼진 브라우저는 켜지 않고 빈 목록이다")
    void listsTabsWhenOn() throws Exception {
        browsers.create(dad.id());
        control.setUser(current(dad), true);

        JsonNode stopped = call("browser_tabs", json.createObjectNode());
        assertThat(text(stopped)).contains("browser_stopped").doesNotContain("TAB1");

        browsers.start(dad.id());
        JsonNode listed = call("browser_tabs", json.createObjectNode());
        assertThat(isError(listed)).as(text(listed)).isFalse();
        assertThat(text(listed)).contains("TAB1", "받은 편지함", "https://mail.example.com/inbox", "TAB2");
    }


    @Test
    @DisplayName("닫기는 카드만 띄우고, 카드는 탭 제목과 사이트를 보이며 실행 칸을 보이지 않는다. 승인하면 그 탭만 닫고 알림 줄에 수만 남긴다")
    void closesAfterApproval() throws Exception {
        turnOn(dad);

        JsonNode result = call("browser_tab_close", tabIds("TAB1"));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(text(result)).contains("approval_requested", "아직 닫지 않았다");
        assertThat(cdp.closed).isEmpty();
        ConnectorAction card = actions.findAll().getFirst();
        assertThat(card.conversationId()).isEqualTo(conversation.id());
        var view = actionService.listForConversation(current(dad), conversation.id()).getFirst();
        assertThat(view.title()).isEqualTo("브라우저 탭 닫기");
        assertThat(view.argsJson()).contains("받은 편지함 · mail.example.com").doesNotContain("targets", "TAB1");

        var approved = actionService.approve(current(dad), card.publicId(), null, false);

        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(cdp.closed).containsExactly("TAB1");
        assertThat(notices()).singleElement().asString().startsWith("탭 1개를 닫았어요").doesNotContain("받은 편지함", "mail.example.com");
    }


    @Test
    @DisplayName("다른 사용자는 그 카드를 승인할 수 없고, 승인 전에 탭이 다른 페이지로 가면 닫지 않는다")
    void otherUserCannotApproveAndMovedTabStays() throws Exception {
        turnOn(dad);
        call("browser_tab_close", tabIds("TAB1", "TAB2"));
        ConnectorAction card = actions.findAll().getFirst();

        assertThatThrownBy(() -> actionService.approve(current(mom), card.publicId(), null, false)).isInstanceOf(ApiException.class);
        assertThat(cdp.closed).isEmpty();

        cdp.pages.removeIf(page -> page.id().equals("TAB2"));
        cdp.pages.add(new CdpTarget("TAB2", "다른 글", "https://news.example.com/other"));
        actionService.approve(current(dad), card.publicId(), null, false);

        assertThat(cdp.closed).containsExactly("TAB1");
        assertThat(cdp.pages).extracting(CdpTarget::id).containsExactly("TAB2");
    }


    @Test
    @DisplayName("카드를 띄운 뒤 사용자가 스위치를 끄면 승인해도 닫지 않고 실패로 끝난다")
    void switchOffBeforeApprovalRefuses() throws Exception {
        turnOn(dad);
        call("browser_tab_close", tabIds("TAB1"));
        ConnectorAction card = actions.findAll().getFirst();
        control.setUser(current(dad), false);

        var finished = actionService.approve(current(dad), card.publicId(), null, false);

        assertThat(finished.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(cdp.closed).isEmpty();
    }


    @Test
    @DisplayName("목록에 없는 탭 번호, 내부 주소는 카드 없이 거절하고 공개 주소는 승인 뒤 연다")
    void validatesBeforeCard() throws Exception {
        turnOn(dad);

        assertThat(text(call("browser_tab_close", tabIds("TAB1", "NOPE")))).contains("unknown_tab");
        assertThat(text(call("browser_tab_open", url("http://192.168.0.1/admin")))).contains("invalid_url");
        assertThat(text(call("browser_tab_open", url("file:///etc/passwd")))).contains("invalid_url");
        assertThat(actions.findAll()).isEmpty();

        JsonNode opened = call("browser_tab_open", url("https://www.example.com/reserve"));
        assertThat(isError(opened)).as(text(opened)).isFalse();
        actionService.approve(current(dad), actions.findAll().getFirst().publicId(), null, false);
        assertThat(cdp.created).containsExactly("https://www.example.com/reserve");
    }


    @Test
    @DisplayName("쓰기를 켜지 않은 먼저 살펴보기에서도 탭을 보고 닫기 카드를 띄운다")
    void checkTreeAcceptsBrowserTools() throws Exception {
        turnOn(dad);
        ProactiveCheck check = ProactiveCheck.started(dad.id(), run.agentId(), conversation.id(), CheckTrigger.MANUAL, false, Instant.now());
        check.attachRoot(run.id(), root);
        ProactiveCheck saved = checks.save(check);
        try {
            JsonNode listed = call("browser_tabs", json.createObjectNode());
            assertThat(isError(listed)).as(text(listed)).isFalse();
            JsonNode closing = call("browser_tab_close", tabIds("TAB2"));
            assertThat(isError(closing)).as(text(closing)).isFalse();
            assertThat(actions.findAll()).singleElement()
                .satisfies(card -> assertThat(card.status()).isEqualTo(ActionStatus.PENDING));
            assertThat(cdp.closed).isEmpty();
        } finally {
            checks.delete(saved);
        }
    }


    private void turnOn(AppUser user) {
        browsers.create(user.id());
        browsers.start(user.id());
        control.setUser(current(user), true);
    }


    private ObjectNode tabIds(String... ids) {
        ObjectNode arguments = json.createObjectNode();
        var array = arguments.putArray("tab_ids");
        for (String id : ids) {
            array.add(id);
        }
        return arguments;
    }


    private ObjectNode url(String value) {
        return json.createObjectNode().put("url", value);
    }


    private List<String> notices() {
        return messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
            .filter(message -> message.role() == MessageRole.SYSTEM)
            .map(message -> message.content())
            .toList();
    }


    private JsonNode call(String tool, ObjectNode arguments) throws Exception {
        ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", tool).set("arguments", arguments);
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
            McpToolResultShape.assertCallToolResult(tool, body.path("result"));
        }
        return body;
    }


    private void clean() {
        actions.deleteAll();
        jdbc.update("DELETE FROM user_browser");
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
