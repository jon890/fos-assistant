package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제로 돈 provider 와 모델을 v0.21.5 응답 모양에서 읽는지 본다.
 *
 * <p>가짜는 운영 Hermes 에서 확인한 모양이다(2026-09-29). 세션 조회는 {@code session} 안에 {@code model} 을 두고
 * provider 칸을 주지 않는다. 실행 조회는 {@code runtime} 에 실제로 돈 provider 와 모델을 싣는다.
 */
class HermesRuntimeReadTest {

    private final HermesProfileKeyStore keyStore = mock(HermesProfileKeyStore.class);
    private final Map<String, String> bodies = new HashMap<>();
    private HttpServer server;
    private HttpHermesRunsClient client;
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        when(keyStore.resolve("dad")).thenReturn("dad-key");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = bodies.getOrDefault(exchange.getRequestURI().getPath(), "{}");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/p/dad";
        client = new HttpHermesRunsClient(
                keyStore,
                new HermesProperties(
                        "keys",
                        "https://hermes-dashboard.example.com",
                        "test-dashboard-token",
                        "https://hermes-listener.example.com",
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("세션 조회는 session 안의 model 을 읽는다")
    void sessionQueryReadsModelInsideSession() {
        bodies.put(
                "/p/dad/api/sessions/sess-1",
                "{\"object\":\"session\",\"session\":{\"id\":\"sess-1\",\"model\":\"gpt-6-luna\"}}");

        SessionRuntime runtime = client.readSessionRuntime(baseUrl, "dad", "sess-1");

        assertThat(runtime).isNotNull();
        assertThat(runtime.model()).isEqualTo("gpt-6-luna");
        assertThat(runtime.provider()).isNull();
    }

    @Test
    @DisplayName("실행 조회는 runtime 의 provider 와 모델을 결과에 싣는다")
    void runQueryCarriesRuntimeProviderAndModelInResult() {
        // 기본값으로 보낸 실행이라 model 칸은 profile 이름을 되돌려 준다.
        bodies.put(
                "/p/dad/v1/runs/run-1",
                "{\"run_id\":\"run-1\",\"status\":\"completed\",\"session_id\":\"sess-1\",\"model\":\"dad\","
                        + "\"runtime\":{\"provider\":\"openai-codex\",\"model\":\"gpt-6-luna\",\"route_source\":\"global\"}}");

        HermesRunResult result = client.awaitCompletion(
                new HermesRunCommand("dad", baseUrl, "안녕", null, null, null, null, null), "run-1");

        assertThat(result.runtime()).isNotNull();
        assertThat(result.runtime().provider()).isEqualTo("openai-codex");
        assertThat(result.runtime().model()).isEqualTo("gpt-6-luna");
        assertThat(result.model()).as("model 칸은 요청을 되돌려 준 값 그대로다").isEqualTo("dad");
    }
}
