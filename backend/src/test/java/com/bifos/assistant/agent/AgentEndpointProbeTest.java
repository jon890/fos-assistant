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
    void 확인이_200_이면_지나간다(@TempDir Path keyDir) throws IOException {
        String baseUrl = startHermes(200);

        probe(keyDir, "dad", "dad-key").requireReachable(baseUrl + "/p/dad", "dad");

        assertThat(calls).singleElement()
                .satisfies(call -> assertThat(call.path()).isEqualTo("/p/dad/v1/capabilities"));
    }

    @Test
    void 확인은_그_에이전트의_profile_key_로_나간다(@TempDir Path keyDir) throws IOException {
        String baseUrl = startHermes(200);

        probe(keyDir, "dad", "dad-key").requireReachable(baseUrl + "/p/dad", "dad");

        assertThat(calls).singleElement()
                .satisfies(call -> assertThat(call.authorization()).isEqualTo("Bearer dad-key"));
    }

    @Test
    void 확인이_200_이_아니면_무엇이_왔는지_알린다(@TempDir Path keyDir) throws IOException {
        String baseUrl = startHermes(401);
        AgentEndpointProbe probe = probe(keyDir, "dad", "dad-key");

        assertThatThrownBy(() -> probe.requireReachable(baseUrl + "/p/dad", "dad"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("401")
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 닿지_않는_주소는_무엇이_막혔는지_알린다(@TempDir Path keyDir) throws IOException {
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
    void 끝의_슬래시를_떼고_묻는다(@TempDir Path keyDir) throws IOException {
        String baseUrl = startHermes(200);

        probe(keyDir, "dad", "dad-key").requireReachable(baseUrl + "/p/dad/", "dad");

        assertThat(calls).singleElement()
                .satisfies(call -> assertThat(call.path()).isEqualTo("/p/dad/v1/capabilities"));
    }

    private AgentEndpointProbe probe(Path keyDir, String profile, String key) throws IOException {
        Files.writeString(keyDir.resolve(profile), key);
        HermesProperties properties = new HermesProperties(keyDir.toString(),
                "https://hermes-dashboard.example.com", "test-dashboard-token",
                Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2));
        return new AgentEndpointProbe(new HermesProfileKeyStore(properties), properties);
    }

    /** 무엇을 물어도 정해진 상태 코드로 답하는 Hermes 대역을 띄운다. */
    private String startHermes(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(new Call(exchange.getRequestURI().getPath(),
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
