package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.SubagentProviderLookup;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 대시보드의 답마다 provider 조회 결과가 「있다」, 「없다」, 「닿지 못했다」 로 맞게 나뉘는지 본다. */
class SubagentProviderClientTest {

    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private SubagentProviderClient client;
    // 검사 스레드가 쓰고 서버 스레드가 읽는다.
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        client = new SubagentProviderClient(new HermesProperties(
                "keys",
                "http://127.0.0.1:" + server.getAddress().getPort(),
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
    @DisplayName("200 에 provider 가 있으면 그 provider 와 모델을 돌려준다")
    void returnsFoundWhenProviderIsPresent() {
        body = "{\"provider\":\"anthropic\",\"model\":\"m\"}";

        SubagentProviderLookup lookup = client.read("dad", "child-1");

        assertThat(lookup).isEqualTo(SubagentProviderLookup.found("anthropic", "m"));
        assertThat(paths).containsExactly("/api/profiles/dad/sessions/child-1/provider");
        assertThat(authorizations).containsExactly("Bearer test-dashboard-token");
    }

    @Test
    @DisplayName("200 에 provider 가 null 이면 없다고 답한다")
    void returnsAbsentWhenProviderIsNull() {
        body = "{\"provider\":null,\"model\":\"m\"}";

        assertThat(client.read("dad", "child-1")).isEqualTo(SubagentProviderLookup.absent());
    }

    @Test
    @DisplayName("404 는 없다고 답한다")
    void returnsAbsentOnNotFound() {
        status = 404;

        assertThat(client.read("dad", "child-1")).isEqualTo(SubagentProviderLookup.absent());
    }

    @Test
    @DisplayName("401 은 다시 읽을 실패로 보지 않고 없다고 답한다")
    void returnsAbsentOnUnauthorized() {
        status = 401;

        SubagentProviderLookup lookup = client.read("dad", "child-1");

        assertThat(lookup).isEqualTo(SubagentProviderLookup.absent());
        assertThat(lookup.unavailable()).as("401 을 기다리면 자식의 토큰이 합계에 들어가지 못한다").isFalse();
    }

    @Test
    @DisplayName("503 은 닿지 못했다고 답한다")
    void returnsUnavailableOnServerError() {
        status = 503;

        assertThat(client.read("dad", "child-1").unavailable()).isTrue();
    }

    @Test
    @DisplayName("서버가 내려가 있으면 예외 없이 닿지 못했다고 답한다")
    void returnsUnavailableWhenServerIsDown() {
        server.stop(0);

        assertThat(client.read("dad", "child-1").unavailable()).isTrue();
    }

    @Test
    @DisplayName("형식이 틀린 profile 이름은 요청을 보내지 않고 없다고 답한다")
    void returnsAbsentWithoutRequestForInvalidProfile() {
        assertThat(client.read("Bad Name", "child-1")).isEqualTo(SubagentProviderLookup.absent());
        assertThat(paths).as("서버가 받은 요청").isEmpty();
    }
}
