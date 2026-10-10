package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.dto.ConnectorApprovedExecution;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpHermesConnectorGuardHttpTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;
    private HttpHermesConnectorClient client;
    private int status = 200;
    private boolean blocked;
    private String answer = "{\"ok\":true,\"result\":{\"accepted\":true}}";

    @TempDir
    Path attachments;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/connectors/demo/", exchange -> {
            calls.incrementAndGet();
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            entered.countDown();
            try {
                if (blocked) {
                    release.await(5, TimeUnit.SECONDS);
                }
                byte[] response = answer.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new HttpHermesConnectorClient(
                new HermesProperties(
                        "unused",
                        url,
                        "test-dashboard-token",
                        url,
                        null,
                        null,
                        Duration.ofMillis(500),
                        Duration.ofMillis(500)),
                new SandboxAttachmentDirectory(attachments.toString()));
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private JsonNode vector(String name) throws Exception {
        return MAPPER.readTree(Files.readString(Path.of("../test/resources/" + name + ".json")));
    }

    private ConnectorApprovedExecution execution() throws Exception {
        JsonNode vector = vector("financial-transport-v1");
        JsonNode ticket = vector("financial-ticket-v1");
        String payload = ticket.path("payload")
                .stringValue()
                .replace("a".repeat(64), vector.path("argsSha256").stringValue());
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String encoded = encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(ticket.path("token").stringValue().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] key = mac.doFinal("fos-approval-signing-key-v1".getBytes(StandardCharsets.US_ASCII));
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal(("fos-approval-claim-v1" + encoded).getBytes(StandardCharsets.US_ASCII)));
        return new ConnectorApprovedExecution(
                encoded + "." + signature,
                vector.path("argsJson").stringValue(),
                vector.path("argsSha256").stringValue());
    }

    @Test
    @DisplayName("실제 HTTP는 서비스 인증과 저장 원문 및 모든 SDK 인자를 한 번 전달한다")
    void approvedPreservesOriginalOverHttp() throws Exception {
        var execution = execution();
        assertThat(client.executeApproved("test-profile", "demo", "mcp__demo__place_order", execution)
                        .ok())
                .isTrue();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(auth.get()).isEqualTo("Bearer test-dashboard-token");
        assertThat(received.get().path("execution").path("argsJson").stringValue())
                .isEqualTo(execution.argsJson());
        assertThat(received.get().path("execution").path("ticket").stringValue())
                .isEqualTo(execution.ticket());
        assertThat(received.get().path("args")).isEqualTo(MAPPER.readTree(execution.argsJson()));
        assertThat(execution.toString()).doesNotContain(execution.ticket(), execution.argsJson());
    }

    @Test
    @DisplayName("해시와 추가 키 및 중복 원문은 HTTP 전에 거절한다")
    void invalidOriginalHasNoHttpCall() throws Exception {
        var execution = execution();
        var invalid = new ConnectorApprovedExecution(execution.ticket(), execution.argsJson(), "a".repeat(64));
        assertThat(client.executeApproved("test-profile", "demo", "tool", invalid)
                        .error())
                .isEqualTo(ConnectorCallError.INVALID_INPUT);
        assertThat(client.prepare("test-profile", "demo", "tool", "{\"x\":1,\"x\":2}")
                        .error())
                .isEqualTo(ConnectorCallError.INVALID_INPUT);
        assertThat(client.prepare("test-profile", "demo", "tool", "{} {}").error())
                .isEqualTo(ConnectorCallError.INVALID_INPUT);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("읽기 전용 prepare의 5xx와 옛 plugin 거절은 재시도 없이 금융을 닫는다")
    void prepareFailureHasNoRetry() {
        for (int code : new int[] {500, 404, 409}) {
            status = code;
            assertThat(client.prepare("test-profile", "demo", "tool", "{}").error())
                    .isEqualTo(ConnectorCallError.UNAVAILABLE);
        }
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("준비 HTTP는 기존 본문으로 인자를 보내고 결과를 strict하게 읽는다")
    void preparePreservesArgumentsAndRejectsDuplicateResult() {
        answer = "{\"ok\":true,\"result\":{\"v\":1,\"executionArgs\":{},\"summary\":{}}}";
        assertThat(client.prepare("test-profile", "demo", "mcp__demo__place_order", "{\"quantity\":\"2\"}")
                        .ok())
                .isTrue();
        assertThat(received.get().propertyNames()).containsExactlyInAnyOrder("profile", "hermes_tool", "args");
        assertThat(received.get().path("args").path("quantity").stringValue()).isEqualTo("2");
        answer = "{\"ok\":true,\"result\":{\"v\":1,\"v\":1,\"executionArgs\":{},\"summary\":{}}}";
        assertThat(client.prepare("test-profile", "demo", "tool", "{}").error())
                .isEqualTo(ConnectorCallError.UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("승인 실행의 5xx는 UNKNOWN이며 다시 전송하지 않는다")
    void executeUnknownHasNoRetry() throws Exception {
        status = 500;
        var execution = execution();
        assertThatThrownBy(() -> client.executeApproved("test-profile", "demo", "tool", execution))
                .isInstanceOf(ConnectorExecutionUnknown.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("실제 준비 HTTP가 멈춰도 2초 제한 뒤 실패하고 요청은 한 번이다")
    void prepareTimeoutHasNoRetry() throws Exception {
        blocked = true;
        try {
            assertThat(client.prepare("test-profile", "demo", "tool", "{}").error())
                    .isEqualTo(ConnectorCallError.UNAVAILABLE);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }
}
