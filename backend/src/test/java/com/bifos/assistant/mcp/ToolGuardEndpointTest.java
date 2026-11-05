package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * 실제 HTTP 경계에서 도구 판정 경로의 인증과 판정 순서를 확인한다(ADR-20261105 / event-watch).
 *
 * <p>판정 순서는 {@code docs/features/proactive/design.md} 의 「사건 실행의 도구 판정」 이다. 서명은 운영 코드가 아니라 이 시험이
 * {@code hermes/plugins/fos-ctx/README.md} 의 「도구 판정」 표대로 따로 계산한다.
 */
@BackendIntegrationTest
class ToolGuardEndpointTest {
    private static final String PATH = "/internal/hermes/tool-guard";
    private static final String PROFILE = "tool-guard-main";
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    /** `hermes/tests/test_fos_ctx.py` 의 `TOOL_GUARD_VECTOR_SIG` 와 같은 확인 값이다. */
    private static final String VECTOR_SIG = "86882b9f368c2edc9d538289b80f0568599e145642f89b357d5e1d2fefe17273";

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    HermesSessionBindingRepository bindings;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String token;
    private AppUser owner;
    private Agent agent;


    @BeforeEach
    void setUp() {
        String ofProfile = "(SELECT id FROM agent WHERE hermes_profile = ?)";
        jdbc.update("DELETE FROM proactive_check WHERE agent_id IN " + ofProfile, PROFILE);
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("DELETE FROM conversation WHERE agent_id IN " + ofProfile, PROFILE);
        jdbc.update("DELETE FROM agent WHERE hermes_profile = ?", PROFILE);
        jdbc.update("DELETE FROM agent_token WHERE profile_name = ?", PROFILE);
        jdbc.update("DELETE FROM app_user WHERE email = ?", "guard-owner@example.com");
        owner = users.save(AppUser.of("guard-owner@example.com", "주인", 1L, UserRole.MEMBER));
        agent = agents.save(Agent.of(
            "bifos-" + UUID.randomUUID(),
            "메인 에이전트",
            PROFILE,
            "http://localhost",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
        token = tokens.issue(PROFILE, "main").rawToken();
    }


    @Test
    @DisplayName("서명 확인 값이 fos-ctx 시험과 같다")
    void signatureMatchesPluginVector() {
        String key = "2ce07fe9da9032a6ba2d14ea44adf290f530110a4b5d2ed67bb72384342abf8b";
        assertThat(hmac(key, String.join("\n", "v1-tool-guard", "terminal", "fos-root-1", "fos-session-1", "call_1")))
            .isEqualTo(VECTOR_SIG);
    }


    @Test
    @DisplayName("사건 살펴보기 turn 의 session 과 그 압축 교체 session 은 block 이다")
    void eventCheckTurnIsBlocked() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation check = checkConversation(root);
        AgentExecution turn = running(root, check.id(), null);
        checkRow(check.id(), CheckTrigger.EVENT, turn.id());

        assertBlocked(ask("terminal", root, root), "새 사건 살펴보기");
        assertBlocked(ask("delegate_task", root, "compressed-" + UUID.randomUUID()), "새 사건 살펴보기");
    }


    @Test
    @DisplayName("사건 살펴보기 트리에 등록된 자식 session 은 block 이다")
    void registeredChildOfEventTreeIsBlocked() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation check = checkConversation(root);
        AgentExecution turn = running(root, check.id(), null);
        checkRow(check.id(), CheckTrigger.EVENT, turn.id());
        String child = "child-" + UUID.randomUUID();
        bindings.saveAndFlush(HermesSessionBinding.of(PROFILE, child, turn, root, root));

        assertBlocked(ask("write_file", root, child), "새 사건 살펴보기");
    }


    @Test
    @DisplayName("같은 에이전트의 보통 대화와 그 압축 교체 session 은 allow 이다")
    void normalConversationIsAllowed() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation normal = conversations.save(Conversation.startedBy(owner.id(), "보통 대화", agent.id(), NOW));
        running(root, normal.id(), null);
        // 같은 profile 에서 사건 살펴보기가 돌아도 보통 대화는 그 실행의 판정을 따른다.
        String eventRoot = "fos-" + UUID.randomUUID();
        Conversation check = checkConversation(eventRoot);
        checkRow(check.id(), CheckTrigger.EVENT, running(eventRoot, check.id(), null).id());

        assertAllowed(ask("terminal", root, root));
        assertAllowed(ask("execute_code", root, "compressed-" + UUID.randomUUID()));
    }


    @Test
    @DisplayName("단추 살펴보기와 점검 대화의 사용자 turn 은 allow 이다")
    void manualCheckAndUserTurnAreAllowed() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation check = checkConversation(root);
        AgentExecution manual = running(root, check.id(), null);
        checkRow(check.id(), CheckTrigger.MANUAL, manual.id());

        assertAllowed(ask("terminal", root, root));
        jdbc.update("UPDATE agent_execution SET status = 'SUCCEEDED' WHERE id = ?", manual.id());
        running(root, check.id(), null);
        assertAllowed(ask("terminal", root, "compressed-" + UUID.randomUUID()));
    }


    @Test
    @DisplayName("점검 대화의 session 인데 도는 실행이 없으면 block 이다")
    void checkConversationWithoutRunningExecutionIsBlocked() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation check = checkConversation(root);
        AgentExecution ended = running(root, check.id(), null);
        checkRow(check.id(), CheckTrigger.EVENT, ended.id());
        jdbc.update("UPDATE agent_execution SET status = 'SUCCEEDED' WHERE id = ?", ended.id());

        assertBlocked(ask("terminal", root, "late-child-" + UUID.randomUUID()), "실행 맥락");
        assertBlocked(ask("terminal", root, root), "실행 맥락");
    }


    @Test
    @DisplayName("모르는 session 은 그 profile 에서 사건 살펴보기가 돌 때만 block 이다")
    void unknownSessionIsBlockedOnlyWhileEventRuns() throws Exception {
        String unknown = "discord-" + UUID.randomUUID();
        assertAllowed(ask("terminal", unknown, unknown));

        String root = "fos-" + UUID.randomUUID();
        Conversation check = checkConversation(root);
        checkRow(check.id(), CheckTrigger.EVENT, running(root, check.id(), null).id());

        assertBlocked(ask("terminal", unknown, unknown), "실행 맥락");
    }


    @Test
    @DisplayName("등록의 루트가 서명한 루트와 다르면 block 이다")
    void registeredRootMismatchIsBlocked() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation normal = conversations.save(Conversation.startedBy(owner.id(), "보통 대화", agent.id(), NOW));
        AgentExecution turn = running(root, normal.id(), null);
        String child = "child-" + UUID.randomUUID();
        bindings.saveAndFlush(HermesSessionBinding.of(PROFILE, child, turn, root, root));

        assertBlocked(ask("terminal", "fos-other", child), "실행 맥락");
        assertAllowed(ask("terminal", root, child));
    }


    @Test
    @DisplayName("서명이 틀리거나 토큰이 없으면 403 이고 404 가 아니다")
    void rejectsWithoutNotFound() throws Exception {
        ObjectNode body = body("terminal", "fos-root", "fos-root");
        body.put("sig", "0".repeat(64));
        assertThat(send(token, body.toString()).statusCode()).isEqualTo(403);
        assertThat(send(token, "not json").statusCode()).isEqualTo(403);
        assertThat(send(null, body("terminal", "fos-root", "fos-root").toString()).statusCode())
            .isIn(401, 403)
            .isNotEqualTo(404);
    }


    private Conversation checkConversation(String root) {
        Conversation conversation = Conversation.startedForCheck(owner.id(), "점검", agent.id(), NOW);
        conversation.assignNewSession(root);
        return conversations.save(conversation);
    }


    private AgentExecution running(String root, Long conversationId, Long rootExecutionId) {
        return executions.save(AgentExecution.builder()
            .userId(owner.id())
            .agentId(agent.id())
            .conversationId(conversationId)
            .profileName(PROFILE)
            .hermesSessionId(root)
            .rootExecutionId(rootExecutionId)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(NOW)
            .build());
    }


    private void checkRow(Long conversationId, CheckTrigger trigger, Long rootExecutionId) {
        ProactiveCheck check = ProactiveCheck.started(owner.id(), agent.id(), conversationId, trigger, false, NOW);
        check.attachRoot(rootExecutionId, null);
        checks.save(check);
    }


    private HttpResponse<String> ask(String tool, String root, String session) throws Exception {
        return send(token, body(tool, root, session).toString());
    }


    private ObjectNode body(String tool, String root, String session) {
        String call = "call_" + UUID.randomUUID();
        ObjectNode body = json.createObjectNode();
        body.put("v", 1);
        body.put("root_session_id", root);
        body.put("session_id", session);
        body.put("tool_call_id", call);
        body.put("hermes_tool", tool);
        body.put("sig", hmac(sha256(token), String.join("\n", "v1-tool-guard", tool, root, session, call)));
        return body;
    }


    private HttpResponse<String> send(String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }


    private void assertAllowed(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("decision").asString()).isEqualTo("allow");
    }


    private void assertBlocked(HttpResponse<String> response, String messagePart) throws Exception {
        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(200);
        JsonNode answer = json.readTree(response.body());
        assertThat(answer.path("decision").asString()).isEqualTo("block");
        assertThat(answer.path("message").asString()).contains(messagePart);
    }


    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    private static String hmac(String key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
