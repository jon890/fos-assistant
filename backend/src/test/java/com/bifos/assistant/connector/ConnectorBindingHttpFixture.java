package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HttpHermesConnectorClient;
import com.bifos.assistant.hermes.SandboxAttachmentDirectory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 실제 Python plugin과 Spring 사이 HTTP만 관측한다. 업무 응답과 guard는 원본 소비자가 만든다. */
final class ConnectorBindingHttpFixture implements AutoCloseable {
    static final JsonMapper JSON = JsonMapper.builder().build();
    final List<JsonNode> wires = new CopyOnWriteArrayList<>();
    final List<JsonNode> callbacks = new CopyOnWriteArrayList<>();
    final List<Integer> callbackCodes = new CopyOnWriteArrayList<>();
    final AtomicReference<Runnable> observeCommit = new AtomicReference<>();
    final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    final AtomicInteger supportFailure = new AtomicInteger();
    final AtomicInteger pluginFailure = new AtomicInteger();
    final AtomicInteger heldFailure = new AtomicInteger();
    final AtomicReference<String> supportDefect = new AtomicReference<>();
    final AtomicReference<CountDownLatch> entered = new AtomicReference<>();
    final AtomicReference<CountDownLatch> release = new AtomicReference<>();
    final HttpHermesConnectorClient actual;
    private final HttpServer bridge;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Process python;
    private final String pythonBase;
    private final String springBase;
    private final String token;


    ConnectorBindingHttpFixture(int springPort, String token) throws Exception {
        this.springBase = "http://127.0.0.1:" + springPort;
        this.token = token;
        bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.setExecutor(threads);
        bridge.createContext("/internal/connector-executions/support", this::support);
        bridge.createContext("/api", this::plugin);
        bridge.start();
        String base = "http://127.0.0.1:" + bridge.getAddress().getPort();
        python = new ProcessBuilder("python3", "../test/e2e/financial/guard_plugin_http.py", base, token)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var ready = threads.submit(() -> new BufferedReader(new InputStreamReader(python.getInputStream())).readLine());
        String line = ready.get(30, TimeUnit.SECONDS);
        assertThat(line).as("Python fixture 준비").isNotNull();
        pythonBase = "http://127.0.0.1:" + JSON.readTree(line).get("port").intValue();
        actual = new HttpHermesConnectorClient(new HermesProperties("unused", base, token, base, null, null,
            Duration.ofSeconds(2), Duration.ofSeconds(10)), new SandboxAttachmentDirectory(System.getProperty("java.io.tmpdir")));
    }


    /** 공용 mock의 호출을 시험 안에서 만든 실제 HTTP 클라이언트에 위임한다. */
    void delegate(HermesConnectorClient hermes) {
        when(hermes.readCatalog()).thenAnswer(invocation -> actual.readCatalog());
        when(hermes.bindConnector(anyString(), anyString(), anyString(), nullable(String.class), nullable(String.class), any(JsonNode.class)))
            .thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return actual.bindConnector(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5));
            });
        when(hermes.bindConnector(anyString(), anyString(), anyString(), nullable(String.class), nullable(String.class)))
            .thenAnswer(invocation -> actual.bindConnector(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                invocation.getArgument(3), invocation.getArgument(4)));
        when(hermes.readConnector(anyString(), anyString())).thenAnswer(invocation -> actual.readConnector(
            invocation.getArgument(0), invocation.getArgument(1)));
        when(hermes.probe(anyString(), anyString())).thenAnswer(invocation -> actual.probe(invocation.getArgument(0), invocation.getArgument(1)));
        when(hermes.unbindConnector(anyString(), anyString())).thenAnswer(invocation -> actual.unbindConnector(
            invocation.getArgument(0), invocation.getArgument(1)));
        when(hermes.deleteVault(anyString())).thenAnswer(invocation -> actual.deleteVault(invocation.getArgument(0)));
        doAnswer(invocation -> {
            actual.putVault(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(hermes).putVault(anyString(), anyString(), any());
        when(hermes.call(anyString(), anyString(), any(), nullable(String.class))).thenAnswer(invocation -> actual.call(
            invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)));
        when(hermes.callWithVault(anyString(), anyString(), anyString(), nullable(String.class))).thenAnswer(invocation -> actual.callWithVault(
            invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)));
    }


    private void support(HttpExchange exchange) throws java.io.IOException {
        try {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + token);
            JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            callbacks.add(request);
            Runnable observation = observeCommit.get();
            if (observation != null) {
                observation.run();
            }
            HttpResponse<String> response = send(springBase + exchange.getRequestURI(), "POST", request.toString());
            callbackCodes.add(response.statusCode());
            int failure = supportFailure.get();
            String defect = supportDefect.get();
            String body = response.body();
            if (response.statusCode() == 200 && defect != null) {
                ObjectNode changed = (ObjectNode)JSON.readTree(body);
                changed.put(defect, defect.equals("nonce") ? "00000000-0000-4000-8000-000000000000" : "b".repeat(64));
                body = changed.toString();
            }
            reply(exchange, failure == 0 ? response.statusCode() : failure, body);
        } catch (Exception | AssertionError error) {
            callbackFailure.set(error);
            reply(exchange, 500, "{}");
            throw new java.io.IOException(error);
        }
    }


    private void plugin(HttpExchange exchange) throws java.io.IOException {
        try {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + token);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean bind = exchange.getRequestMethod().equals("PUT") && exchange.getRequestURI().getPath().equals("/api/connectors");
            if (bind) {
                wires.add(JSON.readTree(body));
            }
            if (bind && pluginFailure.get() != 0) {
                reply(exchange, pluginFailure.get(), "{}");
                return;
            }
            HttpResponse<String> response = send(pythonBase + exchange.getRequestURI(), exchange.getRequestMethod(), body);
            CountDownLatch signal = bind ? entered.getAndSet(null) : null;
            if (signal != null) {
                signal.countDown();
                assertThat(release.get().await(8, TimeUnit.SECONDS)).as("설치 응답 대기 상한").isTrue();
            }
            reply(exchange, signal != null && heldFailure.get() != 0 ? heldFailure.get() : response.statusCode(), response.body());
        } catch (Exception error) {
            reply(exchange, 500, "{}");
            throw new java.io.IOException(error);
        }
    }


    private HttpResponse<String> send(String url, String method, String body) throws Exception {
        var publisher = body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }


    JsonNode inspect() throws Exception {
        return JSON.readTree(send(pythonBase + "/fixture/inspect", "GET", "").body());
    }


    void change(String mode) throws Exception {
        assertThat(send(pythonBase + "/fixture/change", "POST", JSON.createObjectNode().put("mode", mode).toString()).statusCode())
            .isEqualTo(200);
    }


    private static void reply(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }


    @Override
    public void close() throws Exception {
        if (release.get() != null) {
            release.get().countDown();
        }
        python.destroy();
        if (!python.waitFor(5, TimeUnit.SECONDS)) {
            python.destroyForcibly();
        }
        bridge.stop(0);
        threads.shutdownNow();
        http.close();
    }
}
