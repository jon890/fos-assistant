package com.bifos.assistant.hermes.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunLookup;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hermes v0.21.6 과 v0.23.2 의 실행 조회를 같은 뜻으로 읽는지 본다(docs/features/hermes-upgrade/design.md 의 「조회 응답 읽기」).
 *
 * <p>대역 서버는 경로마다 정한 답을 차례로 내고, 다 쓰면 마지막 답을 되풀이한다. 받은 요청은 「메서드 경로 본문」 으로 남긴다.
 */
class HttpHermesRunsClientCompatTest {

    private static final String LOST = "{\"status\":\"unknown\",\"session_id\":\"s1\",\"admission_id\":\"adm-1\","
        + "\"execution_generation\":3,\"output\":\"\",\"usage\":{}}";

    private final Map<String, Deque<Answer>> answers = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;
    private HermesRunTerminals terminals;
    private HttpHermesRunsClient client;


    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        HermesProfileKeyStore keys = mock(HermesProfileKeyStore.class);
        when(keys.resolve("dad")).thenReturn("dad-key");
        HermesProperties properties = new HermesProperties(
            "keys",
            "https://hermes-dashboard.example.com",
            "test-dashboard-token",
            "https://hermes-listener.example.com",
            Duration.ofMillis(10),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2));
        terminals = new HermesRunTerminals();
        HermesRunReplay replay = new HermesRunReplay(keys, properties, JsonMapper.builder().build(), Duration.ofSeconds(2));
        client = new HttpHermesRunsClient(keys, properties, Duration.ofSeconds(1), terminals, replay);
    }


    @AfterEach
    void stop() {
        server.stop(0);
    }


    @Test
    @DisplayName("v0.21.6 모양의 끝난 실행은 지금처럼 읽고 넘어간 steer 글을 안다")
    void readsV021ShapeAsBefore() {
        on("GET /v1/runs/run-1", 200, "{\"status\":\"interrupted\",\"error\":\"gateway stopped\",\"session_id\":\"s1\"}");

        HermesRunLookup lookup = client.lookupRun(baseUrl, "dad", "run-1");

        assertThat(lookup.result().status()).isEqualTo("interrupted");
        assertThat(lookup.result().error()).isEqualTo("gateway stopped");
        assertThat(lookup.result().pendingSteerKnown()).isTrue();
        assertThat(requests).containsExactly("GET /v1/runs/run-1 ");
    }


    @Test
    @DisplayName("v0.21.6 모양의 completed 와 cancelled 는 그대로 읽고, 404 는 모르는 실행이다")
    void readsOtherV021ShapesAsBefore() {
        on("GET /v1/runs/run-1", 200, "{\"status\":\"completed\",\"output\":\"답\",\"pending_steer\":\"남은 글\"}");
        on("GET /v1/runs/run-2", 200, "{\"status\":\"cancelled\",\"output\":\"\"}");
        on("GET /v1/runs/run-3", 404, "{\"error\":{\"code\":\"run_not_found\"}}");

        HermesRunResult completed = client.lookupRun(baseUrl, "dad", "run-1").result();
        HermesRunResult cancelled = client.lookupRun(baseUrl, "dad", "run-2").result();

        assertThat(completed.succeeded()).isTrue();
        assertThat(completed.pendingSteer()).isEqualTo("남은 글");
        assertThat(completed.pendingSteerKnown()).isTrue();
        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(cancelled.pendingSteerKnown()).isTrue();
        assertThat(client.lookupRun(baseUrl, "dad", "run-3").state()).isEqualTo(HermesRunLookup.State.NOT_FOUND);
        assertThat(requests).noneMatch(request -> request.contains("/events") || request.contains("resolve-unknown"));
    }


    @Test
    @DisplayName("v0.23.2 의 queued 는 도는 것으로 보고 다시 물어 끝난 결과를 받는다")
    void queuedKeepsPolling() {
        on("GET /v1/runs/run-1", 200,
            "{\"status\":\"queued\",\"admission_id\":\"adm-1\",\"execution_generation\":1}",
            "{\"status\":\"completed\",\"admission_id\":\"adm-1\",\"execution_generation\":1,\"output\":\"답\"}");
        on("GET /v1/runs/run-1/events", 404, "");

        HermesRunResult result = client.awaitCompletion(command(), "run-1");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).isEqualTo("답");
        assertThat(result.pendingSteerKnown()).isFalse();
    }


    @Test
    @DisplayName("잃은 실행은 조회 경로에서 두 칸으로 해제하고 Hermes 가 모르는 실행으로 돌려준다")
    void lostRunIsReleasedOnLookup() {
        on("GET /v1/runs/run-1", 200, LOST);
        on("POST /v1/runs/run-1/resolve-unknown", 200, "{\"run_id\":\"run-1\"}");

        HermesRunLookup lookup = client.lookupRun(baseUrl, "dad", "run-1");

        assertThat(lookup.state()).isEqualTo(HermesRunLookup.State.NOT_FOUND);
        assertThat(requests).containsExactly(
            "GET /v1/runs/run-1 ",
            "POST /v1/runs/run-1/resolve-unknown {\"admission_id\":\"adm-1\",\"execution_generation\":3}");
        assertThat(terminals.settled("dad", "run-1")).isTrue();
    }


    @Test
    @DisplayName("turn 을 기다리다 잃은 실행을 만나면 해제하고 REMOTE_RUN_LOST 로 실패한다")
    void lostRunFailsTheTurn() {
        on("GET /v1/runs/run-1", 200, LOST);
        on("POST /v1/runs/run-1/resolve-unknown", 200, "{}");

        assertThatThrownBy(() -> client.awaitCompletion(command(), "run-1"))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REMOTE_RUN_LOST));
        assertThat(requests).contains(
            "POST /v1/runs/run-1/resolve-unknown {\"admission_id\":\"adm-1\",\"execution_generation\":3}");
    }


    @Test
    @DisplayName("해제 요청이 닿지 않으면 실패로 올리고 해제한 것으로 기억하지 않는다")
    void connectionFailureIsNotTreatedAsReleased() {
        HermesSessionRuns unreachable = new HermesSessionRuns(
            new HermesProperties("keys", "https://d.example.com", "t", "https://l.example.com",
                Duration.ofMillis(10), Duration.ofMillis(200), Duration.ofMillis(200), Duration.ofSeconds(1)),
            terminals,
            null);
        JsonNode lost = JsonMapper.builder().build().readTree(LOST);

        assertThatThrownBy(() -> unreachable.resolve("http://127.0.0.1:1", "dad-key", "dad", "run-1", lost))
            .isInstanceOf(ApiException.class);
        assertThat(terminals.settled("dad", "run-1")).isFalse();
    }


    @Test
    @DisplayName("해제가 409 면 다시 물어 풀렸으면 해제로 보고, 아직 잃은 상태면 실패로 올린다")
    void conflictRereadsBeforeTrusting() {
        on("GET /v1/runs/run-1", 200, LOST,
            "{\"status\":\"cancelled\",\"admission_id\":\"adm-1\",\"execution_generation\":3}");
        on("POST /v1/runs/run-1/resolve-unknown", 409, "{\"error\":{\"code\":\"stale_generation\"}}");
        assertThat(client.lookupRun(baseUrl, "dad", "run-1").state()).isEqualTo(HermesRunLookup.State.NOT_FOUND);

        on("GET /v1/runs/run-2", 200, LOST.replace("adm-1", "adm-2"));
        on("POST /v1/runs/run-2/resolve-unknown", 409, "{\"error\":{\"code\":\"stale_generation\"}}");
        assertThatThrownBy(() -> client.lookupRun(baseUrl, "dad", "run-2")).isInstanceOf(ApiException.class);
        assertThat(terminals.settled("dad", "run-2")).isFalse();
    }


    @Test
    @DisplayName("해제가 500 이면 실패로 올리고 다음 조회에서 다시 해제를 보낸다")
    void serverErrorRetriesOnNextLookup() {
        on("GET /v1/runs/run-1", 200, LOST);
        on("POST /v1/runs/run-1/resolve-unknown", 500, "{}", "{}");

        assertThatThrownBy(() -> client.lookupRun(baseUrl, "dad", "run-1")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.lookupRun(baseUrl, "dad", "run-1")).isInstanceOf(ApiException.class);
        assertThat(requests).filteredOn(request -> request.startsWith("POST")).hasSize(2);
    }


    @Test
    @DisplayName("v0.23.2 의 cancelled 는 끝 사건과 중지 기록으로 사용자 중지와 정상 종료로 끊긴 실행을 나눈다")
    void cancelledIsSplitByTerminalAndStopRecord() {
        String cancelled = "{\"status\":\"cancelled\",\"admission_id\":\"adm-1\",\"execution_generation\":1}";
        on("GET /v1/runs/run-1/events", 404, "");

        on("GET /v1/runs/run-1", 200, cancelled);
        terminals.record("dad", "run-1", terminal("run.cancelled", null, false));
        assertThat(client.lookupRun(baseUrl, "dad", "run-1").result().status()).isEqualTo("cancelled");

        terminals.record("dad", "run-1", terminal("run.interrupted", null, false));
        HermesRunResult interrupted = client.lookupRun(baseUrl, "dad", "run-1").result();
        assertThat(interrupted.status()).isEqualTo("interrupted");
        assertThat(interrupted.pendingSteerKnown()).isFalse();

        assertThat(client.lookupRun(baseUrl, "dad", "run-1").result().status()).isEqualTo("interrupted");

        on("POST /v1/runs/run-1/stop", 200, "{\"status\":\"stopping\"}");
        client.stop(baseUrl, "dad", "run-1");
        assertThat(client.lookupRun(baseUrl, "dad", "run-1").result().status()).isEqualTo("cancelled");
    }


    @Test
    @DisplayName("조회에 없는 error 와 pending_steer 는 남은 끝 사건이나 보관분 다시 읽기로 채우고, 둘 다 없으면 모른다고 표시한다")
    void fillsMissingFieldsFromTerminal() {
        String failed = "{\"status\":\"failed\",\"admission_id\":\"adm-1\",\"execution_generation\":1}";
        on("GET /v1/runs/run-1", 200, failed);
        terminals.record("dad", "run-1", terminal("run.failed", "⚠️ Provider authentication failed: all", true));
        HermesRunResult kept = client.lookupRun(baseUrl, "dad", "run-1").result();
        assertThat(kept.providerBlocked()).isTrue();
        assertThat(kept.pendingSteer()).isEqualTo("넘어간 글");
        assertThat(kept.pendingSteerKnown()).isTrue();

        on("GET /v1/runs/run-1/events", 200,
            "data: {\"event\": \"run.failed\", \"error\": \"provider down\", \"pending_steer\": null}\n\n");
        HermesRunResult replayed = client.lookupRun(baseUrl, "dad", "run-1").result();
        assertThat(replayed.error()).isEqualTo("provider down");
        assertThat(replayed.pendingSteerKnown()).isTrue();

        on("GET /v1/runs/run-1/events", 404, "");
        HermesRunResult unknown = client.lookupRun(baseUrl, "dad", "run-1").result();
        assertThat(unknown.error()).isNull();
        assertThat(unknown.pendingSteerKnown()).isFalse();
    }


    @Test
    @DisplayName("잃은 실행에 보낸 중지가 409 unknown_execution 이면 해제하고 정상으로 돌아온다")
    void stopOfLostRunReleasesIt() {
        on("POST /v1/runs/run-1/stop", 409, "{\"error\":{\"code\":\"unknown_execution\",\"message\":\"unknown_execution\"}}");
        on("GET /v1/runs/run-1", 200, LOST);
        on("POST /v1/runs/run-1/resolve-unknown", 200, "{}");

        client.stop(baseUrl, "dad", "run-1");

        assertThat(requests).anyMatch(request -> request.startsWith("POST /v1/runs/run-1/resolve-unknown"));
        assertThat(terminals.stopped("dad", "run-1")).isTrue();
    }


    @Test
    @DisplayName("미리 풀기는 잃은 실행만 해제하고 끝난 실행은 기억해 다시 묻지 않으며 모르는 실행은 그냥 둔다")
    void releaseIfLostTouchesOnlyLostRuns() {
        on("GET /v1/runs/run-1", 200, LOST);
        on("POST /v1/runs/run-1/resolve-unknown", 200, "{}");
        on("GET /v1/runs/run-2", 200, "{\"status\":\"completed\",\"admission_id\":\"adm-2\",\"execution_generation\":1}");
        on("GET /v1/runs/run-3", 404, "{}");

        client.releaseIfLost(baseUrl, "dad", "run-1");
        client.releaseIfLost(baseUrl, "dad", "run-2");
        client.releaseIfLost(baseUrl, "dad", "run-2");
        client.releaseIfLost(baseUrl, "dad", "run-3");
        client.releaseIfLost(baseUrl, "dad", "run-3");

        assertThat(requests).containsExactly(
            "GET /v1/runs/run-1 ",
            "POST /v1/runs/run-1/resolve-unknown {\"admission_id\":\"adm-1\",\"execution_generation\":3}",
            "GET /v1/runs/run-2 ",
            "GET /v1/runs/run-3 ");
    }


    private static HermesRunTerminal terminal(String event, String error, boolean withPending) {
        return new HermesRunTerminal(event, error, withPending ? "넘어간 글" : null, withPending, null, null);
    }


    private HermesRunCommand command() {
        return new HermesRunCommand("dad", baseUrl, "안녕", null, null, null, null, null);
    }


    private void on(String request, int status, String... bodies) {
        Deque<Answer> queue = new ArrayDeque<>();
        for (String body : bodies) {
            queue.add(new Answer(status, body));
        }
        answers.put(request, queue);
    }


    private void answer(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(key + " " + body);
        Deque<Answer> queue = answers.get(key);
        Answer answer = queue == null ? new Answer(404, "{}") : queue.size() > 1 ? queue.poll() : queue.peek();
        byte[] payload = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
            "Content-Type", key.endsWith("/events") ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(answer.status(), payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            exchange.getResponseBody().write(payload);
        }
        exchange.close();
    }


    private record Answer(int status, String body) {
    }
}
