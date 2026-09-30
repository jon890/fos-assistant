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
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
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
                "keys", "http://127.0.0.1:" + server.getAddress().getPort() + "/", TOKEN, "http://listener.test",
                Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 게시는_profile_과_external_dirs_만_싣고_도구_목록이_있으면_함께_싣는다() throws Exception {
        client.publish("kid", List.of("/skills/kid/v1790661162144-a1b2"), null);
        client.publish("kid", List.of(), List.of("web", "skills", "fos-assistant"));

        assertThat(calls).hasSize(2).allSatisfy(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.path()).isEqualTo("/api/config");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
        });
        assertThat(new ObjectMapper().readValue(calls.get(0).body(), Map.class)).isEqualTo(Map.of(
                "profile", "kid",
                "config", Map.of("skills", Map.of("external_dirs", List.of("/skills/kid/v1790661162144-a1b2")))));
        assertThat(new ObjectMapper().readValue(calls.get(1).body(), Map.class)).isEqualTo(Map.of(
                "profile", "kid",
                "config", Map.of(
                        "skills", Map.of("external_dirs", List.of()),
                        "platform_toolsets", Map.of("api_server", List.of("web", "skills", "fos-assistant")))));
    }

    @Test
    void 목록은_query_profile_로_묻고_배열을_읽는다() {
        response = "[{\"name\":\"weekly-plan\",\"description\":\"이번 주 계획\",\"category\":\"agent\","
                + "\"enabled\":false,\"usage\":0,\"provenance\":\"agent\"},{\"name\":\"bare\"}]";

        assertThat(client.list("kid")).containsExactly(
                new HermesSkill("weekly-plan", "이번 주 계획", false),
                new HermesSkill("bare", "", true));
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.path()).isEqualTo("/api/skills");
            assertThat(call.query()).isEqualTo("profile=kid");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
        });
    }

    @Test
    void 빈_배열은_스킬이_없는_profile_이고_배열이_아니면_받지_않는다() {
        response = "[]";
        assertThat(client.list("kid")).isEmpty();

        response = "{\"skills\":[]}";
        assertThatThrownBy(() -> client.list("kid"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
    }

    @Test
    void 켜고_끄기는_profile_과_이름과_값을_함께_싣는다() throws Exception {
        client.toggle("kid", "weekly-plan", false);

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.path()).isEqualTo("/api/skills/toggle");
            assertThat(new ObjectMapper().readValue(call.body(), Map.class))
                    .isEqualTo(Map.of("profile", "kid", "name", "weekly-plan", "enabled", false));
        });
    }

    @Test
    void 게시가_4xx_면_거절_예외이고_5xx_면_닿지_못한_것과_같은_예외다() {
        status = 400;
        response = "{\"error\":\"directory does not exist\"}";
        assertThatThrownBy(() -> client.publish("kid", List.of("/skills/kid/v1790661162144-a1b2"), null))
                .isInstanceOfSatisfying(HermesRequestRejected.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
                });

        status = 500;
        assertThatThrownBy(() -> client.publish("kid", List.of("/skills/kid/v1790661162144-a1b2"), null))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(HermesRequestRejected.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    void profile_이름이_규칙에_맞지_않으면_대시보드를_부르지_않는다() {
        assertThatThrownBy(() -> client.list("Kid/../x"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(calls).isEmpty();
    }
}
