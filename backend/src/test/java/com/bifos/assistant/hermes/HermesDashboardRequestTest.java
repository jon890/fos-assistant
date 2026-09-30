package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.dto.SoulDocument;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 대시보드를 부를 때 나가는 메서드와 경로와 본문과 토큰을 본다.
 *
 * <p>여기가 어긋나면 대역은 통과하고 운영에서만 401 이나 404 가 난다. 기계용 토큰은 경로 문자열이
 * 정확히 같은 요청만 열어 주기 때문이다.
 */
class HermesDashboardRequestTest {

    private static final String TOKEN = "test-dashboard-token";

    private final List<Call> calls = new ArrayList<>();

    private HttpServer server;
    private HttpHermesDashboardClient client;
    private int status = 200;
    private String responseBody = "{}";

    private record Call(String method, String path, String authorization, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(
                    new Call(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            new String(
                                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            // 대시보드는 JSON 으로 답한다. 이 머리글이 없으면 응답을 읽는 호출만 변환기를 찾지 못한다.
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        client = clientFor(baseUrl());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respondWith(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    private HttpHermesDashboardClient clientFor(String baseUrl) {
        return new HttpHermesDashboardClient(
                new HermesProperties(
                        "keys",
                        baseUrl,
                        TOKEN,
                        "https://hermes-listener.example.com",
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("profile 을 만들 때 본뜰 profile 을 주지 않고 번들 스킬을 심지 않는다")
    void createsProfileWithoutCloneSourceAndBundledSkills() {
        client.createProfile("kid");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.path()).isEqualTo("/api/profiles");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
            // 칸 순서는 정해져 있지 않으므로 문자열이 아니라 JSON 으로 읽어 비교한다.
            assertThat(new ObjectMapper().readValue(call.body(), Map.class))
                    .isEqualTo(Map.of("name", "kid", "no_skills", true));
        });
    }

    @Test
    @DisplayName("환경 값은 profile 과 이름과 값을 함께 싣는다")
    void envValueCarriesProfileNameAndValue() {
        client.putEnv("kid", "API_SERVER_MODEL_NAME", "kid");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.path()).isEqualTo("/api/env");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(call.body())
                    .contains("\"profile\":\"kid\"")
                    .contains("\"key\":\"API_SERVER_MODEL_NAME\"")
                    .contains("\"value\":\"kid\"");
        });
    }

    @Test
    @DisplayName("profile 을 지울 때 그 이름이 경로에 붙는다")
    void appendsNameToPathWhenDeletingProfile() {
        client.deleteProfile("kid");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("DELETE");
            assertThat(call.path()).isEqualTo("/api/profiles/kid");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
        });
    }

    /** 지우다 실패한 뒤 다시 지울 때 끝까지 가려면, 없는 profile 은 이미 지운 것으로 봐야 한다. */
    @Test
    @DisplayName("지울 profile 이 없다는 404 는 정상으로 끝난다")
    void treatsNoSuchProfile404OnDeleteAsNormalEnd() {
        respondWith(404, "{\"error\":\"no such profile\"}");

        client.deleteProfile("kid");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("DELETE");
            assertThat(call.path()).isEqualTo("/api/profiles/kid");
        });
    }

    @Test
    @DisplayName("profile 을 지우다 500 을 받으면 닿지 못한 것으로 남는다")
    void remainsUnreachableWhenProfileDeleteGets500() {
        respondWith(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client.deleteProfile("kid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    @DisplayName("SOUL 을 읽을 때 그 이름이 경로에 붙는다")
    void appendsNameToPathWhenReadingSoul() {
        respondWith(200, "{\"content\":\"너는 아빠다\",\"exists\":true}");

        SoulDocument soul = client.readSoul("kid");

        assertThat(soul).isEqualTo(new SoulDocument("너는 아빠다", true));
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.path()).isEqualTo("/api/profiles/kid/soul");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
        });
    }

    @Test
    @DisplayName("SOUL 을 쓸 때 본문이 content 한 칸이다")
    void soulWriteBodyIsSingleContentField() {
        client.putSoul("kid", "너는 아빠다");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.path()).isEqualTo("/api/profiles/kid/soul");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(call.body()).isEqualTo("{\"content\":\"너는 아빠다\"}");
        });
    }

    @Test
    @DisplayName("규칙에 맞지 않는 profile 이름은 대시보드를 부르지 않는다")
    void doesNotCallDashboardForProfileNameBreakingRule() {
        assertThatThrownBy(() -> client.readSoul("../etc"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> client.putSoul("Kid/soul", "너는 아빠다"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("주소 끝에 빗금이 붙어 있어도 경로가 겹치지 않는다")
    void pathsDoNotOverlapEvenIfUrlEndsWithSlash() {
        clientFor(baseUrl() + "/").createProfile("kid");

        assertThat(calls)
                .singleElement()
                .satisfies(call -> assertThat(call.path()).isEqualTo("/api/profiles"));
    }

    @Test
    @DisplayName("이미 쓰이는 이름이면 그것으로 알린다")
    void reportsNameAlreadyInUse() {
        respondWith(409, "{\"error\":\"profile already exists\"}");

        assertThatThrownBy(() -> client.createProfile("kid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_PROFILE_EXISTS);
    }

    @Test
    @DisplayName("다른 실패는 닿지 못한 것으로 남는다")
    void otherFailuresRemainAsUnreachable() {
        respondWith(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client.createProfile("kid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }
}
