package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
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
 * 실제 HTTP 경계에서 예약 작업 도구의 인자 검사, 실패 결과, 요청자 판정, 먼저 살펴보기의 받는 조건을 본다(스펙 service-concepts
 * REQ7-REQ10, REQ17, REQ18). 도구 호출은 이 검사의 profile 로 도는 아빠의 실행 루트로 서명한다(ADR-032).
 */
@BackendIntegrationTest
class McpTaskToolsTest {
    private static final String PROFILE = "mcp-task-tools";

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AgentTokenRepository tokenRows;

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
    TaskRepository tasks;

    @Autowired
    TaskRunRepository runs;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private Agent momsAgent;
    private Conversation conversation;
    private String token;
    private String root;
    private AgentExecution run;


    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        tokenRows.deleteAll();
        clean();
        dad = users.save(AppUser.of("task-dad-" + UUID.randomUUID() + "@example.com", "아빠", 1L, UserRole.MEMBER));
        AppUser mom = users.save(AppUser.of("task-mom-" + UUID.randomUUID() + "@example.com", "엄마", 1L, UserRole.MEMBER));
        // 엄마가 그룹에 공개한 에이전트를 아빠가 쓴다. 검사 클래스들이 H2 하나를 함께 써서 앞 검사의 에이전트가 남으면 그것을 쓴다.
        momsAgent = agents.findAll().stream()
            .filter(agent -> PROFILE.equals(agent.hermesProfile()))
            .findFirst()
            .orElseGet(() -> agents.save(Agent.of(PROFILE, "엄마의 비서", PROFILE, "http://runtime.test/p/" + PROFILE,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.GROUP, mom.id())));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "날씨", momsAgent.id(), Instant.now()));
        token = tokens.issue(PROFILE, "task").rawToken();
        root = McpCallSigner.newRoot();
        run = executions.save(AgentExecution.builder()
            .userId(dad.id())
            .agentId(momsAgent.id())
            .conversationId(conversation.id())
            .profileName(PROFILE)
            .hermesSessionId(root)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(Instant.parse("2026-11-20T00:00:00Z"))
            .build());
    }


    @AfterEach
    void tearDown() {
        clean();
    }


    @Test
    @DisplayName("그룹 공개 에이전트로 만든 작업은 요청자의 것이고 결과에 작업 화면 주소가 있다")
    void createsTaskOwnedByRequester() throws Exception {
        JsonNode result = call("task_create", create("아침 날씨", "0 7 * * *"));

        assertThat(isError(result)).as(text(result)).isFalse();
        JsonNode body = json.readTree(text(result));
        assertThat(body.path("status").asString()).isEqualTo("created");
        assertThat(body.path("task").path("link").asString()).startsWith("/tasks/");
        assertThat(tasks.countByOwnerUserIdAndKindAndStateNot(dad.id(), TaskKind.TURN, TaskState.ARCHIVED)).isEqualTo(1);
        assertThat(tasks.countByOwnerUserIdAndKindAndStateNot(momsAgent.ownerUserId(), TaskKind.TURN, TaskState.ARCHIVED)).isZero();
    }


    @Test
    @DisplayName("스키마 밖 인자는 인자 오류이고, 틀린 시각은 하지 못한 까닭이 담긴 실패 결과다")
    void rejectsArgumentsAndReportsFailures() throws Exception {
        ObjectNode both = create("둘 다", "0 7 * * *").put("fire_at", "2026-11-21T07:00");
        ObjectNode model = json.createObjectNode().put("task", "아침 날씨").put("model_tier", "PRO");
        ObjectNode state = json.createObjectNode().put("task", "아침 날씨").put("state", "deleted");

        assertThat(call("task_create", both).has("error")).isTrue();
        assertThat(call("task_update", model).has("error")).isTrue();
        assertThat(call("task_set_state", state).has("error")).isTrue();

        JsonNode invalid = call("task_create", create("틀린 시각", "0 25 * * *"));
        assertThat(isError(invalid)).isTrue();
        assertThat(text(invalid)).contains("invalid_schedule", "하지 않았다");
        JsonNode missing = call("task_delete", json.createObjectNode().put("task", "없는 작업"));
        assertThat(isError(missing)).isTrue();
        assertThat(text(missing)).contains("not_found");
    }


    @Test
    @DisplayName("쓰기를 켜지 않은 살펴보기는 만들기를 거절하고 지우기 카드는 띄우며, 쓰기를 켠 살펴보기는 만든다")
    void checkTreeAcceptsByWriteSwitch() throws Exception {
        call("task_create", create("아침 날씨", "0 7 * * *"));
        ProactiveCheck refused = ProactiveCheck.started(dad.id(), momsAgent.id(), conversation.id(), CheckTrigger.MANUAL, false, Instant.now());
        refused.attachRoot(run.id(), root);
        ProactiveCheck saved = checks.save(refused);
        try {
            JsonNode created = call("task_create", create("살펴보기 작업", "0 8 * * *"));
            assertThat(isError(created)).isTrue();
            assertThat(tasks.countByOwnerUserIdAndKindAndStateNot(dad.id(), TaskKind.TURN, TaskState.ARCHIVED)).isEqualTo(1);

            JsonNode deleted = call("task_delete", json.createObjectNode().put("task", "아침 날씨"));
            assertThat(isError(deleted)).as(text(deleted)).isFalse();
            assertThat(text(deleted)).contains("approval_requested");
            assertThat(actions.findAll()).singleElement().satisfies(card -> {
                assertThat(ControlPlaneActions.owns(card)).isTrue();
                assertThat(card.status()).isEqualTo(ActionStatus.PENDING);
            });
            assertThat(tasks.countByOwnerUserIdAndKindAndStateNot(dad.id(), TaskKind.TURN, TaskState.ARCHIVED)).isEqualTo(1);
        } finally {
            checks.delete(saved);
        }

        ProactiveCheck allowed = ProactiveCheck.started(dad.id(), momsAgent.id(), conversation.id(), CheckTrigger.MANUAL, true, Instant.now());
        allowed.attachRoot(run.id(), root);
        ProactiveCheck savedAllowed = checks.save(allowed);
        try {
            JsonNode created = call("task_create", create("살펴보기 작업", "0 8 * * *"));
            assertThat(isError(created)).as(text(created)).isFalse();
        } finally {
            checks.delete(savedAllowed);
        }
    }


    private ObjectNode create(String title, String cron) {
        return json.createObjectNode().put("title", title).put("instruction", title + " 를 확인해 알린다").put("cron", cron);
    }


    private JsonNode call(String tool, ObjectNode arguments) throws Exception {
        ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", tool).set("arguments", arguments);
        String signed = McpCallSigner.withContext(request.toString(), token, root);
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(signed))
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
        runs.deleteAll();
        tasks.deleteAll();
    }


    private static boolean isError(JsonNode result) {
        return result.path("result").path("isError").asBoolean();
    }


    private static String text(JsonNode result) {
        return result.path("result").path("content").get(0).path("text").asString();
    }
}
