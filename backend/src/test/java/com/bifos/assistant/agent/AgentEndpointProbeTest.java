package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.hermes.HermesProfileKeyStore;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 주소를 저장하기 전에 닿는지 보는 확인을 검사한다. */
class AgentEndpointProbeTest {

    /** 확인이 실제로 부른 경로와 Authorization 헤더다. */
    private record Call(String path, String authorization) {}

    private final List<Call> calls = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("확인이 200 이면 지나간다")
    void passesWhenProbeReturns200(@TempDir Path keyDir) throws IOException {
        String baseUrl = startHermes(200);

        probe(keyDir, "dad", "dad-key").requireReachable(baseUrl + "/p/dad", "dad");

        assertThat(calls)
                .singleElement()
                .satisfies(call -> assertThat(call.path()).isEqualTo("/p/dad/v1/capabilities"));
    }

    @Test
    @DisplayName("확인은 그 에이전트의 profile key 로 나간다")
    void sendsProbeWithAgentProfileKey(@TempDir Path keyDir) throws IOException {
        String baseUrl = startHermes(200);

        probe(keyDir, "dad", "dad-key").requireReachable(baseUrl + "/p/dad", "dad");

        assertThat(calls)
                .singleElement()
                .satisfies(call -> assertThat(call.authorization()).isEqualTo("Bearer dad-key"));
    }

    @Test
    @DisplayName("확인이 200 이 아니면 무엇이 왔는지 알린다")
    void reportsReceivedStatusWhenNot200(@TempDir Path keyDir) throws IOException {
        String baseUrl = startHermes(401);
        AgentEndpointProbe probe = probe(keyDir, "dad", "dad-key");

        assertThatThrownBy(() -> probe.requireReachable(baseUrl + "/p/dad", "dad"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("401")
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("닿지 않는 주소는 무엇이 막혔는지 알린다")
    void reportsWhatBlockedUnreachableUrl(@TempDir Path keyDir) throws IOException {
        // 아무도 듣지 않는 포트를 고르려고 띄운 뒤 바로 내린다.
        String baseUrl = startHermes(200);
        server.stop(0);
        server = null;
        AgentEndpointProbe probe = probe(keyDir, "dad", "dad-key");

        assertThatThrownBy(() -> probe.requireReachable(baseUrl + "/p/dad", "dad"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("could not reach");
    }

    @Test
    @DisplayName("끝의 슬래시를 떼고 묻는다")
    void stripsTrailingSlashBeforeProbing(@TempDir Path keyDir) throws IOException {
        String baseUrl = startHermes(200);

        probe(keyDir, "dad", "dad-key").requireReachable(baseUrl + "/p/dad/", "dad");

        assertThat(calls)
                .singleElement()
                .satisfies(call -> assertThat(call.path()).isEqualTo("/p/dad/v1/capabilities"));
    }

    private AgentEndpointProbe probe(Path keyDir, String profile, String key) throws IOException {
        Files.writeString(keyDir.resolve(profile), key);
        HermesProperties properties = new HermesProperties(
                keyDir.toString(),
                "https://hermes-dashboard.example.com",
                "test-dashboard-token",
                "https://hermes-listener.example.com",
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));
        return new AgentEndpointProbe(new HermesProfileKeyStore(properties), properties);
    }

    /** 무엇을 물어도 정해진 상태 코드로 답하는 Hermes 대역을 띄운다. */
    private String startHermes(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(new Call(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
