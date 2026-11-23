package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.runtime.domain.TokenUsage;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * 대화 turn 의 {@code agent_delegate} 가 정한 시간까지 결과를 기다려 같은 도구 결과로 돌려주는 것을 실제 {@code /mcp} 경계에서
 * 고정한다(ADR-20261121 / delegate-wait, 스펙 assistant-hub REQ1, REQ2).
 *
 * <p>상한은 이 검사만 짧게 켠다. 다른 검사는 {@code application-test.yml} 의 0 으로 지금처럼 바로 {@code RUNNING} 을 받는다.
 * 상한 안에 받아 간 결과는 전했다고 적혀 깨우기가 다시 열지 않는다. 그 판정은 {@code DelegationWakeServiceTest} 가 본다.
 */
@BackendIntegrationTest
@OverrideProperties("assistant.delegation.delegate-wait-max=3s")
class McpDelegateWaitTest {
    private static final String SHARED = "wait-shared";
    private static final String TARGET_CODE = "wait-target";

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AgentTokenRepository tokenRepository;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final CountDownLatch release = new CountDownLatch(1);
    private AppUser user;
    private String token;


    @BeforeEach
    void setUp() {
        stub().reset();
        McpCallSigner.clearRuns(jdbc, List.of(SHARED, profileOf(TARGET_CODE)));
        agents.findByCode(TARGET_CODE).ifPresent(agents::delete);
        tokenRepository.deleteAll();
        users.deleteAll();
        user = users.save(AppUser.of("wait-a@example.com", "가", 1L, UserRole.MEMBER));
        token = tokens.issue(SHARED, "shared").rawToken();
        agents.save(Agent.of(
            TARGET_CODE,
            "쇼핑 담당",
            profileOf(TARGET_CODE),
            "http://agent-runtime.test/p/" + TARGET_CODE,
            CostMode.API,
            CredentialScope.DEDICATED,
            AgentVisibility.GROUP,
            user.id()));
    }


    @AfterEach
    void releaseHeldChild() {
        release.countDown();
    }


    @Test
    @DisplayName("상한 안에 끝난 맡김은 결과를 함께 돌려주고 전했다고 적는다")
    void returnsResultAndMarksDeliveredWhenChildEndsWithinLimit() throws Exception {
        stub().willAnswer(McpDelegateWaitTest::completed);
        String root = McpCallSigner.newRoot();
        turn(root);

        JsonNode result = result(delegate(root, "운동화 찾아 줘"));

        assertThat(result.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(result.path("output").asString()).isEqualTo("맡은 일의 답: 운동화 찾아 줘");
        AgentExecution child = awaitFinished(result.path("execution_id").asLong());
        assertThat(child.resultDeliveredAt()).as("받아 간 결과는 깨우기가 다시 열지 않는다").isNotNull();
    }


    @Test
    @DisplayName("상한을 넘긴 맡김은 RUNNING 과 짧게 끝내라는 note 를 주고 전했다고 적지 않는다")
    void returnsRunningWithNoteWhenChildOutlastsLimit() throws Exception {
        stub().willAnswer(McpDelegateWaitTest::completed);
        holdChild();
        String root = McpCallSigner.newRoot();
        turn(root);

        long started = System.nanoTime();
        JsonNode result = result(delegate(root, "오래 걸리는 일"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(result.path("status").asString()).isEqualTo("RUNNING");
        assertThat(result.path("note").asString()).contains("결과를 짐작해 쓰지 않는다");
        assertThat(result.has("output")).isFalse();
        release.countDown();
        AgentExecution child = awaitFinished(result.path("execution_id").asLong());
        assertThat(child.resultDeliveredAt()).as("결과는 다음 답이 전한다").isNull();
    }


    @Test
    @DisplayName("같은 답의 두 번째 맡김은 남은 시간만 기다린다")
    void secondDelegationInSameAnswerWaitsOnlyRemainingTime() throws Exception {
        stub().willAnswer(McpDelegateWaitTest::completed);
        holdChild();
        String root = McpCallSigner.newRoot();
        turn(root);

        JsonNode first = result(delegate(root, "첫 일"));
        long started = System.nanoTime();
        JsonNode second = result(delegate(root, "둘째 일"));

        assertThat(first.path("status").asString()).isEqualTo("RUNNING");
        assertThat(second.path("status").asString()).isEqualTo("RUNNING");
        assertThat(second.path("note").asString()).as("예산을 다 쓴 맡김도 짧게 끝내라고 받는다").isNotEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("예산을 다 썼다").isLessThan(Duration.ofSeconds(2));
        release.countDown();
        awaitFinished(first.path("execution_id").asLong());
        awaitFinished(second.path("execution_id").asLong());
    }


    @Test
    @DisplayName("맡은 에이전트가 다시 맡긴 손자 위임은 기다리지 않는다")
    void doesNotWaitForGrandchildDelegation() throws Exception {
        stub().willAnswer(McpDelegateWaitTest::completed);
        holdChild();
        String root = McpCallSigner.newRoot();
        AgentExecution parent = turn(root);
        String childSession = McpCallSigner.newRoot();
        executions.save(AgentExecution.builder()
            .userId(user.id())
            .conversationId(parent.conversationId())
            .parentExecutionId(parent.id())
            .rootExecutionId(parent.id())
            .profileName(SHARED)
            .hermesSessionId(childSession)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(Instant.now())
            .build());

        long started = System.nanoTime();
        // 맡은 실행이 부르는 호출은 그 실행의 session 을 루트로 서명한다(McpAgentToolsTest 의 깊이 검사와 같다)
        JsonNode result = result(delegate(childSession, "손자 일"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(result.path("status").asString()).isEqualTo("RUNNING");
        release.countDown();
        awaitFinished(result.path("execution_id").asLong());
    }


    private void holdChild() {
        stub().beforeAwait(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }


    private AgentExecution turn(String root) {
        Long agentId = agents.findByCode(TARGET_CODE).orElseThrow().id();
        Conversation conversation = conversations.save(Conversation.startedBy(user.id(), "맡기기", agentId, Instant.now()));
        return McpCallSigner.running(executions, user.id(), conversation.id(), SHARED, root);
    }


    private HttpResponse<String> delegate(String root, String task) throws Exception {
        return send(toolCall(arguments(task),
            McpCallSigner.context(token, "agent_delegate", root, root, "call_" + UUID.randomUUID())));
    }


    private ObjectNode arguments(String task) {
        return json.createObjectNode().put("agent_code", TARGET_CODE).put("task", task);
    }


    private String toolCall(ObjectNode arguments, ObjectNode fosCtx) {
        arguments.set("_fos_ctx", fosCtx);
        ObjectNode params = json.createObjectNode();
        params.put("name", "agent_delegate");
        params.set("arguments", arguments);
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);
        return json.writeValueAsString(request);
    }


    private HttpResponse<String> send(String request) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }


    private JsonNode result(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("HTTP 상태, 본문: %s", response.body()).isEqualTo(200);
        JsonNode result = json.readTree(response.body()).path("result");
        assertThat(result.path("isError").asBoolean()).as("도구 결과: %s", result).isFalse();
        return json.readTree(result.path("content").get(0).path("text").asString());
    }


    /** 위임은 가상 스레드에서 돈다. 다음 검사의 준비가 줄을 지우기 전에 끝나야 한다. */
    private AgentExecution awaitFinished(Long executionId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            AgentExecution execution = executions.findById(executionId).orElseThrow();
            boolean ended = executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId)).stream()
                .anyMatch(event -> event.eventType() != ExecutionEventType.RUN_STARTED);
            if (execution.status() != ExecutionStatus.RUNNING && ended) {
                return execution;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("실행 " + executionId + " 이 10초 안에 끝나지 않았다. 상태: " + execution.status());
            }
            Thread.sleep(10);
        }
    }


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    private static HermesRunResult completed(HermesRunCommand command) {
        return HermesRunResult.of(
            "run-" + UUID.randomUUID(),
            command.sessionId(),
            "completed",
            "맡은 일의 답: " + command.input(),
            "example-model",
            "example-provider",
            new TokenUsage(3L, 0L, 2L, 5L));
    }


    private static String profileOf(String code) {
        return "profile-of-" + code;
    }
}
