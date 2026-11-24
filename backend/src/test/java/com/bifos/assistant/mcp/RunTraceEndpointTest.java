package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunEventKind;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 plugin 의 도구 기록이 서명 확인, 실행 풀기, 가림을 거쳐 도구 완료 줄에 옮겨지는지 본다(스펙 run-trace REQ2, REQ6).
 *
 * <p>서명은 운영 코드가 아니라 이 시험이 {@code hermes/plugins/fos-ctx/README.md} 의 「도구 기록을 보낸다」 표대로 따로 계산한다.
 */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RunTraceEndpointTest {
    private static final String PATH = "/internal/hermes/run-trace";
    private static final String PROFILE = "run-trace-main";
    private static final String OTHER_PROFILE = "run-trace-other";
    /** {@code hermes/tests/test_fos_ctx_run_trace.py} 의 확인 값과 같다. */
    static final String VECTOR_KEY = "2ce07fe9da9032a6ba2d14ea44adf290f530110a4b5d2ed67bb72384342abf8b";
    static final String VECTOR_SIG = "00838c8bbb3d1461b00deb843c093dc6ad9103c44211569433b6f9e74e283436";

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
    ExecutionEventRepository events;

    @Autowired
    ExecutionEventRecorder recorder;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String token;
    private String otherToken;
    private AppUser owner;
    private Agent agent;
    private Instant now;


    @BeforeEach
    void setUp() {
        for (String profile : List.of(PROFILE, OTHER_PROFILE)) {
            jdbc.update("DELETE FROM execution_event WHERE execution_id IN (SELECT id FROM agent_execution WHERE profile_name = ?)", profile);
            McpCallSigner.clearRuns(jdbc, List.of(profile));
            jdbc.update("DELETE FROM agent WHERE hermes_profile = ?", profile);
            jdbc.update("DELETE FROM agent_token WHERE profile_name = ?", profile);
        }
        jdbc.update("DELETE FROM app_user WHERE email = ?", "trace-owner@example.com");
        owner = users.save(AppUser.of("trace-owner@example.com", "주인", 1L, UserRole.MEMBER));
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
        otherToken = tokens.issue(OTHER_PROFILE, "main").rawToken();
        now = Instant.now();
    }


    @Test
    @DisplayName("서명 확인 값이 fos-ctx 시험과 같다")
    void signatureMatchesPluginVector() {
        assertThat(hmac(VECTOR_KEY, String.join("\n", "v1-run-trace", "tool", "fos-root-1", "fos-session-1", "call_1")))
            .isEqualTo(VECTOR_SIG);
    }


    @Test
    @DisplayName("저장된 실패 줄에 가린 오류 요약과 받은 그대로의 오류 종류가 옮겨지고 원문은 저장값과 로그에 없다")
    void movesRedactedErrorToSavedCompletedRow(CapturedOutput output) throws Exception {
        String root = "fos-" + UUID.randomUUID();
        AgentExecution run = running(root, now.minusSeconds(30));
        ExecutionEvent completed = completed(run, "call_1", "terminal", true);
        String secret = "fedcba9876543210fedcba9876543210fedcba98";
        String message = "합성원문표식 " + "가".repeat(185) + " " + secret + " " + "나".repeat(100) + " " + UUID.randomUUID();

        HttpResponse<String> response = send(token, batch(record(token, root, root, "call_1", "terminal", "error", "PermissionError", message)));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(json.readTree(response.body()).path("accepted").asInt()).isEqualTo(1);
        ExecutionEvent stored = events.findById(completed.id()).orElseThrow();
        assertThat(stored.errorType()).isEqualTo("PermissionError");
        assertThat(stored.errorSummary()).hasSize(200).startsWith("합성원문표식").endsWith("…").doesNotContain("fedcba");
        assertThat(stored.durationMs()).isEqualTo(1_200L);
        assertThat(output.getAll()).doesNotContain("합성원문표식", secret);
    }


    @Test
    @DisplayName("기록이 완료 줄보다 먼저 와도 완료 줄을 저장할 때 옮겨진다")
    void traceArrivingFirstIsMovedWhenCompletedRowIsBuilt() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        AgentExecution run = running(root, now.minusSeconds(30));

        send(token, batch(record(token, root, root, "call_2", "read_file", "error", "tool_error", "파일이 없다")));
        ExecutionEvent built = recorder.record(run, toolCompleted("read_file", "call_2", true), 1);
        events.save(built);
        recorder.saved(built);

        ExecutionEvent stored = events.findById(built.id()).orElseThrow();
        assertThat(stored.errorSummary()).isEqualTo("파일이 없다");
        assertThat(stored.errorType()).isEqualTo("tool_error");
    }


    @Test
    @DisplayName("실행이 끝난 직후 온 기록도 그 시각에 돌던 실행에 붙고 다음 turn 에 붙지 않는다")
    void lateTraceGoesToRunOfThatTime() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        AgentExecution earlier = finished(root, now.minusSeconds(60), now.minusSeconds(5));
        ExecutionEvent completed = completed(earlier, "call_3", "terminal", true);
        running(root, now.minusSeconds(2));

        ObjectNode late = record(token, root, root, "call_3", "terminal", "error", "tool_error", "시간 초과");
        late.put("ended_at", (now.minusSeconds(10).toEpochMilli()) / 1000.0);
        send(token, batch(late));

        assertThat(events.findById(completed.id()).orElseThrow().errorSummary()).isEqualTo("시간 초과");
    }


    @Test
    @DisplayName("서명이 틀리거나 다른 profile 의 토큰이면 줄을 바꾸지 않는다")
    void rejectsWrongSignatureAndOtherProfile() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        AgentExecution run = running(root, now.minusSeconds(30));
        ExecutionEvent completed = completed(run, "call_4", "terminal", true);

        ObjectNode forged = record(token, root, root, "call_4", "terminal", "error", "tool_error", "위조");
        forged.put("sig", "0".repeat(64));
        HttpResponse<String> forgedResponse = send(token, batch(forged));
        HttpResponse<String> otherResponse = send(otherToken,
            batch(record(otherToken, root, root, "call_4", "terminal", "error", "tool_error", "남의 기록")));

        assertThat(forgedResponse.statusCode()).isEqualTo(202);
        assertThat(json.readTree(forgedResponse.body()).path("accepted").asInt()).isZero();
        assertThat(json.readTree(otherResponse.body()).path("accepted").asInt()).isZero();
        assertThat(events.findById(completed.id()).orElseThrow().errorSummary()).isNull();
        assertThat(send(null, batch(forged)).statusCode()).isEqualTo(401);
    }


    @Test
    @DisplayName("실패 표시가 없는 완료 줄에는 오류를 옮기지 않고 걸린 시간만 채운다")
    void keepsErrorOffRowsNotMarkedFailed() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        AgentExecution run = running(root, now.minusSeconds(30));
        ExecutionEvent completed = completed(run, "call_5", "write_file", false);

        send(token, batch(record(token, root, root, "call_5", "write_file", "error", "tool_error", "경고만 있었다")));

        ExecutionEvent stored = events.findById(completed.id()).orElseThrow();
        assertThat(stored.errorType()).isNull();
        assertThat(stored.errorSummary()).isNull();
        assertThat(stored.durationMs()).isEqualTo(1_200L);
    }


    private AgentExecution running(String root, Instant startedAt) {
        return executions.save(AgentExecution.builder()
            .userId(owner.id())
            .agentId(agent.id())
            .profileName(PROFILE)
            .hermesSessionId(root)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(startedAt)
            .build());
    }


    private AgentExecution finished(String root, Instant startedAt, Instant finishedAt) {
        return executions.save(AgentExecution.builder()
            .userId(owner.id())
            .agentId(agent.id())
            .profileName(PROFILE)
            .hermesSessionId(root)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .timing(startedAt, finishedAt)
            .build());
    }


    private ExecutionEvent completed(AgentExecution run, String callId, String tool, boolean failed) {
        ExecutionEvent event = events.save(ExecutionEvent.builder()
            .executionId(run.id())
            .sequence(1)
            .eventType(ExecutionEventType.TOOL_COMPLETED)
            .toolName(tool)
            .toolCallId(callId)
            .failed(failed)
            .occurredAt(Instant.now())
            .build());
        recorder.saved(event);
        return event;
    }


    private static RunEvent toolCompleted(String tool, String callId, boolean failed) {
        return new RunEvent(RunEventKind.TOOL_COMPLETED, null, tool, null, null, failed, null, null, null, null, null, null,
            null, null, null, null, null, callId);
    }


    private ObjectNode record(String bearer, String root, String session, String callId, String tool, String status,
        String errorType, String message) {
        ObjectNode node = json.createObjectNode();
        node.put("kind", "tool");
        node.put("root_session_id", root);
        node.put("session_id", session);
        node.put("tool_call_id", callId);
        node.put("tool_name", tool);
        node.put("duration_ms", 1_200);
        node.put("status", status);
        node.put("error_type", errorType);
        node.put("error_message", message);
        node.put("ended_at", now.toEpochMilli() / 1000.0);
        node.put("sig", hmac(sha256(bearer), String.join("\n", "v1-run-trace", "tool", root, session, callId)));
        return node;
    }


    private String batch(ObjectNode... records) {
        ObjectNode body = json.createObjectNode();
        body.put("v", 1);
        ArrayNode items = body.putArray("records");
        for (ObjectNode record : records) {
            items.add(record);
        }
        return body.toString();
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


    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
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
