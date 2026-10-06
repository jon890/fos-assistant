package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
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

/** Hermes 스킬 경로의 메서드, 인증, 본문 모양과 거절을 가르는 규칙을 본다. */
class HermesSkillRequestTest {

    private static final String TOKEN = "dashboard-token";

    private final List<Call> calls = new ArrayList<>();
    private HttpServer server;
    private HttpHermesSkillClient client;
    private int status = 200;
    private String response = "{}";

    private record Call(String method, String path, String query, String authorization, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(new Call(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new HttpHermesSkillClient(new HermesProperties(
                "keys",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                TOKEN,
                "http://listener.test",
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("게시는 profile 과 external dirs, 실행 공간 주인을 싣고 도구 목록이 있으면 함께 싣는다")
    void publishCarriesProfileExternalDirsAndSandboxOwnerPlusToolListIfPresent() throws Exception {
        client.publish("kid", List.of("/skills/kid/v1790661162144-a1b2"), null, "u7");
        client.publish("kid", List.of(), List.of("web", "skills", "fos-assistant"), "a12");

        assertThat(calls).hasSize(2).allSatisfy(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.path()).isEqualTo("/api/config");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
        });
        assertThat(new ObjectMapper().readValue(calls.get(0).body(), Map.class))
                .isEqualTo(Map.of(
                        "profile",
                        "kid",
                        "config",
                        Map.of("skills", Map.of("external_dirs", List.of("/skills/kid/v1790661162144-a1b2"))),
                        "sandbox_owner",
                        "u7"));
        assertThat(new ObjectMapper().readValue(calls.get(1).body(), Map.class))
                .isEqualTo(Map.of(
                        "profile",
                        "kid",
                        "config",
                        Map.of(
                                "skills", Map.of("external_dirs", List.of()),
                                "platform_toolsets", Map.of("api_server", List.of("web", "skills", "fos-assistant"))),
                        "sandbox_owner",
                        "a12"));
    }

    @Test
    @DisplayName("게시가 409 sandbox_unavailable 이면 실행 공간 오류 코드의 거절 예외다")
    void publishConflictWithSandboxUnavailableIsRejectionWithSandboxCode() {
        status = 409;
        response = "{\"detail\":\"sandbox is not configured\",\"code\":\"sandbox_unavailable\"}";

        assertThatThrownBy(() -> client.publish(
                        "kid", List.of("/skills/kid/v1790661162144-a1b2"), List.of("terminal", "skills"), "u7"))
                .isInstanceOfSatisfying(HermesRequestRejected.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_SANDBOX_UNAVAILABLE);
                });

        response = "{\"code\":\"profile_busy\"}";
        assertThatThrownBy(() -> client.publish(
                        "kid", List.of("/skills/kid/v1790661162144-a1b2"), List.of("terminal", "skills"), "u7"))
                .isInstanceOfSatisfying(
                        HermesRequestRejected.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
    }

    @Test
    @DisplayName("목록은 query profile 로 묻고 배열을 읽는다")
    void listAsksByQueryProfileAndReadsArray() {
        response = "[{\"name\":\"weekly-plan\",\"description\":\"이번 주 계획\",\"category\":\"agent\","
                + "\"enabled\":false,\"usage\":0,\"provenance\":\"agent\"},{\"name\":\"bare\"}]";

        assertThat(client.list("kid"))
                .containsExactly(new HermesSkill("weekly-plan", "이번 주 계획", false), new HermesSkill("bare", "", true));
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.path()).isEqualTo("/api/skills");
            assertThat(call.query()).isEqualTo("profile=kid");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
        });
    }

    @Test
    @DisplayName("빈 배열은 스킬이 없는 profile 이고 배열이 아니면 받지 않는다")
    void emptyArrayMeansProfileWithoutSkillsAndNonArrayIsRejected() {
        response = "[]";
        assertThat(client.list("kid")).isEmpty();

        response = "{\"skills\":[]}";
        assertThatThrownBy(() -> client.list("kid"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
    }

    @Test
    @DisplayName("켜고 끄기는 profile 과 이름과 값을 함께 싣는다")
    void toggleCarriesProfileNameAndValue() throws Exception {
        client.toggle("kid", "weekly-plan", false);

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.path()).isEqualTo("/api/skills/toggle");
            assertThat(new ObjectMapper().readValue(call.body(), Map.class))
                    .isEqualTo(Map.of("profile", "kid", "name", "weekly-plan", "enabled", false));
        });
    }

    @Test
    @DisplayName("게시가 4xx 면 거절 예외이고 5xx 면 닿지 못한 것과 같은 예외다")
    void publishGives4xxRejectionExceptionAnd5xxSameAsUnreachable() {
        status = 400;
        response = "{\"error\":\"directory does not exist\"}";
        assertThatThrownBy(() -> client.publish("kid", List.of("/skills/kid/v1790661162144-a1b2"), null, "u7"))
                .isInstanceOfSatisfying(HermesRequestRejected.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
                });

        status = 500;
        assertThatThrownBy(() -> client.publish("kid", List.of("/skills/kid/v1790661162144-a1b2"), null, "u7"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(HermesRequestRejected.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    @DisplayName("profile 이름이 규칙에 맞지 않으면 대시보드를 부르지 않는다")
    void doesNotCallDashboardForProfileNameBreakingRule() {
        assertThatThrownBy(() -> client.list("Kid/../x"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(calls).isEmpty();
    }
}
