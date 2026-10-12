package com.bifos.assistant.hermes;

import com.bifos.assistant.connector.application.ContentDraftWorkerStatusDecoder;
import com.bifos.assistant.connector.application.model.ContentDraftSaveTicketPayload;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bifos.assistant.chat.application.ContentDraftSnapshotHash;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class HttpContentDraftSnapshotClientTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private ExecutorService executor;
    private HttpContentDraftSnapshotClient client;
    private JsonNode golden;
    private final AtomicReference<byte[]> response = new AtomicReference<>();
    private final AtomicReference<JsonNode> seen = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private int status = 200;
    private volatile boolean stall;
    private volatile boolean truncate;
    private volatile boolean delayBody;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final CountDownLatch slotsArrived = new CountDownLatch(4);
    private final CountDownLatch release = new CountDownLatch(1);


    @BeforeEach
    void setUp() throws Exception {
        logs.start();
        ((Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
        golden = json.readTree(Path.of("../hermes/connectors/naver-blog/tests/fixtures/content-snapshot-v1.json").toFile());
        response.set(json.writeValueAsBytes(answer()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/connectors/snapshot-test/snapshot", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
            seen.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            slotsArrived.countDown();
            try {
                if (delayBody) {
                    exchange.sendResponseHeaders(status, 0);
                }
                if (stall) {
                    assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
                }
                var bytes = response.get();
                if (!delayBody) {
                    exchange.sendResponseHeaders(status, truncate ? bytes.length + 1 : 0);
                }
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = new HttpContentDraftSnapshotClient(new HermesProperties(null, "http://127.0.0.1:" + server.getAddress().getPort(),
            "synthetic-token", null, null, null, null, null));
    }


    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
        ((Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("BODY_MARKER", "/private/token", "rawHTTPargs"));
        logs.stop();
    }


    @Test
    @DisplayName("현재 Python catalog producer와 실제 TS compiler golden을 HTTP로 decode한다")
    void decodesActualPythonCatalogAndCompilerGolden() throws Exception {
        var process = new ProcessBuilder("python3", "../test/support/content-snapshot-python-fixture.py")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            byte[] output = process.getInputStream().readNBytes(524289);
            assertThat(output.length).isLessThanOrEqualTo(524288);
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            var generated = json.readTree(output);
            response.set(json.writeValueAsBytes(generated.path("answer")));
            server.createContext("/api/connectors/catalog", exchange -> {
                byte[] body = json.writeValueAsBytes(generated.get("catalog"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            var catalog = new HttpHermesConnectorClient(new HermesProperties(null,
                "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-token", null, null, null, null, null),
                new SandboxAttachmentDirectory(System.getProperty("java.io.tmpdir")));
            assertThat(catalog.readCatalog().getFirst().snapshot().capabilities()).containsEntry("compile", true).containsEntry("start", false);
            assertThat(compile()).isEqualTo(golden.path("snapshot"));
            assertThat(generated.path("payloadHex").stringValue()).isEqualTo(golden.path("payloadUtf8Hex").stringValue());
        } finally {
            process.destroyForcibly();
        }
    }


    @Test
    @DisplayName("실제 HTTP는 POST Bearer와 원고 원본 tuple만 전달한다")
    void sendsExactOriginalRequestTuple() {
        assertThat(compile()).isEqualTo(golden.get("snapshot"));
        assertThat(seen.get().propertyNames()).isEqualTo(Set.of("profile", "operation", "args"));
        assertThat(seen.get().path("profile").stringValue()).isEqualTo("synthetic-profile");
        assertThat(seen.get().path("operation").stringValue()).isEqualTo("compile");
        assertThat(seen.get().path("args").path("canonicalDraft")).isEqualTo(golden.get("draft"));
        assertThat(seen.get().path("args").path("resolvedAssets")).isEqualTo(golden.get("assets"));
        ContentDraftSnapshotHash.verify(golden.get("draft"), golden.get("assets"), compile());
    }


    @Test
    @DisplayName("틀린 capability envelope schema와 revision을 안전한 오류로 거절한다")
    void rejectsMalformedEnvelopeCapabilitiesAndTuple() {
        for (String field : Set.of("compile", "start", "status")) {
            var answer = answer();
            ((ObjectNode)answer.path("capabilities")).put(field, !"compile".equals(field));
            reject(answer);
        }
        var answer = answer();
        ((ObjectNode)answer.path("capabilities")).remove("status");
        reject(answer);
        for (String field : Set.of("schemaVersion", "revision")) {
            answer = answer();
            ((ObjectNode)answer.path("result")).put(field, 999);
            reject(answer);
        }
        answer = answer();
        answer.put("rawHTTPargs", "BODY_MARKER");
        reject(answer);
    }


    @Test
    @DisplayName("chunked 512KiB와 compiled 256KiB 응답을 JSON tree 전에 제한한다")
    void capsChunkedAndCompiledResponses() {
        response.set(new byte[524289]);
        unavailable();
        var answer = answer();
        ((ObjectNode)answer.path("result").path("payload")).put("body", "x".repeat(262144));
        reject(answer);
    }


    @Test
    @DisplayName("canonical 64KiB와 원본 50개 상한은 HTTP 전에 거절한다")
    void rejectsOversizedInputsBeforeHttp() {
        var draft = golden.path("draft").deepCopy();
        ((ObjectNode)draft).put("title", "😀".repeat(17000));
        assertThatThrownBy(() -> client.compile("synthetic-profile", "snapshot-test", draft, golden.get("assets")))
            .isInstanceOf(ApiException.class);
        var assets = json.createArrayNode();
        for (int i = 0; i < 51; i++) {
            assets.add(golden.path("assets").get(0));
        }
        assertThatThrownBy(() -> client.compile("synthetic-profile", "snapshot-test", golden.get("draft"), assets))
            .isInstanceOf(ApiException.class);
        assertThat(seen.get()).isNull();
    }


    @Test
    @DisplayName("오류 body와 잘린 HTTP 응답의 원문 cause를 내보내지 않는다")
    void hidesRawErrorsAndRejectsLostResponse() {
        status = 500;
        response.set("BODY_MARKER /private/token rawHTTPargs".getBytes(StandardCharsets.UTF_8));
        unavailable();
        status = 200;
        response.set("{\"ok\":true,\"ok\":false,\"error\":\"BODY_MARKER\"}".getBytes(StandardCharsets.UTF_8));
        unavailable();
        response.set(json.writeValueAsBytes(answer()));
        truncate = true;
        unavailable();
    }


    @Test
    @DisplayName("5초 전체 응답 한도와 호출 중단은 slot을 반환한다")
    void timesOutAndCancelsWithoutLeakingSlots() throws Exception {
        stall = true;
        delayBody = true;
        var start = System.nanoTime();
        unavailable();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(7));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var interrupted = pool.submit(() -> {
                Thread.currentThread().interrupt();
                unavailable();
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                Thread.interrupted();
            });
            interrupted.get(2, TimeUnit.SECONDS);
        }
        stall = false;
        release.countDown();
        assertThat(compile()).isEqualTo(golden.get("snapshot"));
    }


    @Test
    @DisplayName("네 slot이 사용 중이면 다섯 번째 호출을 HTTP 없이 거절한다")
    void sharesFourCompilerSlots() throws Exception {
        stall = true;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = IntStream.range(0, 4).mapToObj(i -> pool.submit(this::compile)).toList();
            assertThat(slotsArrived.await(2, TimeUnit.SECONDS)).isTrue();
            unavailable();
            stall = false;
            release.countDown();
            for (var call : pending) {
                assertThat(call.get(3, TimeUnit.SECONDS)).isEqualTo(golden.get("snapshot"));
            }
        }
    }


    private ObjectNode answer() {
        var node = json.createObjectNode().put("ok", true).set("result", golden.get("snapshot").deepCopy());
        node.set("capabilities", json.createObjectNode().put("compile", true).put("start", false).put("status", false));
        return node;
    }


    @Test
    @DisplayName("실제 start·status HTTP body는 예약 ID·stamp와 ticket을 분리하고 자동 재전송하지 않는다")
    void sendsReservedStartAndStatusBodiesWithoutRetry() {
        var job = job();
        var stamp = ContentDraftWorkerStatusDecoder.stamp("synthetic-profile", job);
        var result = json.createObjectNode().put("job_id", job.jobId().toString()).set("stamp", json.valueToTree(stamp))
            .put("status", "running").putNull("error_code");
        response.set(json.writeValueAsBytes(jobAnswer(result)));
        assertThat(client.start("synthetic-profile", "snapshot-test", golden.path("snapshot"), stamp, "TICKET_MARKER")).isEqualTo(result);
        assertThat(seen.get().path("operation").stringValue()).isEqualTo("start");
        assertThat(seen.get().path("args").propertyNames()).isEqualTo(Set.of("snapshot", "jobId", "ticket", "stamp"));
        assertThat(seen.get().path("args").path("ticket").stringValue()).isEqualTo("TICKET_MARKER");
        client.status("synthetic-profile", "snapshot-test", stamp);
        assertThat(seen.get().path("operation").stringValue()).isEqualTo("status");
        assertThat(seen.get().path("args").propertyNames()).isEqualTo(Set.of("stamp"));
        assertThat(seen.get().toString()).doesNotContain("TICKET_MARKER", "payload");
        assertThat(requests.get()).isEqualTo(2);
        ((ObjectNode)result.path("stamp")).put("profileHash", "b".repeat(64));
        response.set(json.writeValueAsBytes(jobAnswer(result)));
        assertThatThrownBy(() -> client.status("synthetic-profile", "snapshot-test", stamp))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE));
        assertThat(requests.get()).isEqualTo(3);
    }


    @Test
    @DisplayName("status 응답 timeout은 재전송 없이 취소하고 본문·ticket·원문 cause를 내보내지 않는다")
    void cancelsStatusTimeoutWithoutRetryOrDiagnosticsBody() {
        stall = true;
        delayBody = true;
        var stamp = ContentDraftWorkerStatusDecoder.stamp("synthetic-profile", job());
        var started = System.nanoTime();
        assertThatThrownBy(() -> client.status("synthetic-profile", "snapshot-test", stamp)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE);
            assertThat(ex.getCause()).isNull();
        });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(7));
        assertThat(requests.get()).isEqualTo(1);
    }


    private ContentDraftSaveTicketPayload job() {
        return new ContentDraftSaveTicketPayload(UUID.randomUUID(),
            UUID.randomUUID(), golden.path("snapshot").path("snapshotHash").stringValue(), UUID.randomUUID(),
            1L, 2L, 3L, 4L, Instant.parse("2026-10-11T00:00:00Z"), Instant.parse("2026-10-11T00:00:00.123400Z"), null, null);
    }


    private ObjectNode jobAnswer(JsonNode result) {
        return json.createObjectNode().put("ok", true).set("result", result)
            .set("capabilities", json.createObjectNode().put("compile", true).put("start", true).put("status", true));
    }


    private JsonNode compile() {
        return client.compile("synthetic-profile", "snapshot-test", golden.get("draft"), golden.get("assets"));
    }


    private void reject(JsonNode answer) {
        response.set(json.writeValueAsBytes(answer));
        unavailable();
    }


    private void unavailable() {
        assertThatThrownBy(this::compile).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
            assertThat(ex.getCause()).isNull();
            assertThat(ex.getMessage()).doesNotContain("BODY_MARKER", "private", "token", "rawHTTPargs");
        });
    }
}
