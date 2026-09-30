package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 고를 수 있는 모델 목록을 Hermes 의 실제 응답 모양에서 읽는 것을 본다.
 *
 * <p>설정하지 않은 provider 가 빈 행으로 함께 오는 것과, 모델별 reasoning 지원 표가
 * {@code capabilities} 아래에 있는 것이 실제 응답의 모양이다.
 */
class HermesModelCatalogTest {

    private static final String KEY = "test-profile-key";

    private static final String REAL_SHAPE = """
            {
              "provider": "openai-codex",
              "model": "example-model",
              "providers": [
                {
                  "slug": "openai-codex",
                  "name": "OpenAI Codex",
                  "authenticated": true,
                  "models": ["example-model", "example-model-mini"],
                  "capabilities": {
                    "example-model": {"reasoning": true},
                    "example-model-mini": {"reasoning": false}
                  }
                },
                {
                  "slug": "unconfigured",
                  "name": "Unconfigured",
                  "authenticated": false,
                  "models": []
                },
                {
                  "slug": "empty-but-authenticated",
                  "name": "Empty",
                  "authenticated": true,
                  "models": []
                }
              ]
            }
            """;

    private final List<String> authorizations = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();

    private HttpServer server;
    private HermesModelClient client;
    private int status = 200;
    private String responseBody = REAL_SHAPE;

    @BeforeEach
    void start(@TempDir Path keyDir) throws IOException {
        Files.writeString(keyDir.resolve("dad"), KEY);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        HermesProperties properties = new HermesProperties(
                keyDir.toString(),
                baseUrl(),
                "dashboard-token",
                "https://hermes-listener.example.com",
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
        client = new HermesModelClient(new HermesProfileKeyStore(properties), properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/p/dad";
    }

    @Test
    @DisplayName("기본값과 인증된 provider 의 모델을 읽고 설정하지 않은 provider 는 뺀다")
    void readsDefaultAndAuthenticatedProviderModelsAndDropsUnconfigured() {
        HermesModelCatalog catalog = client.readCatalog(baseUrl(), "dad");

        assertThat(catalog.defaultProvider()).isEqualTo("openai-codex");
        assertThat(catalog.defaultModel()).isEqualTo("example-model");
        assertThat(catalog.providers()).singleElement().satisfies(provider -> {
            assertThat(provider.slug()).isEqualTo("openai-codex");
            assertThat(provider.name()).isEqualTo("OpenAI Codex");
            assertThat(provider.models()).containsExactly("example-model", "example-model-mini");
        });
    }

    @Test
    @DisplayName("capabilities 의 reasoning 을 모델 이름 표로 옮긴다")
    void mapsCapabilitiesReasoningToModelNameTable() {
        HermesModelCatalog catalog = client.readCatalog(baseUrl(), "dad");

        assertThat(catalog.providers().get(0).reasoning())
                .isEqualTo(Map.of("example-model", true, "example-model-mini", false));
    }

    @Test
    @DisplayName("capabilities 에 없는 모델은 표에 넣지 않는다")
    void leavesModelsMissingFromCapabilitiesOutOfTable() {
        respondWith(200, """
                {"providers": [{"slug": "p", "authenticated": true, "models": ["a", "b"],
                 "capabilities": {"a": {"reasoning": true}, "b": {}}}]}
                """);

        HermesModelCatalog catalog = client.readCatalog(baseUrl(), "dad");

        assertThat(catalog.defaultProvider()).isNull();
        assertThat(catalog.defaultModel()).isNull();
        assertThat(catalog.providers().get(0).name()).isEqualTo("p");
        assertThat(catalog.providers().get(0).reasoning()).isEqualTo(Map.of("a", true));
    }

    @Test
    @DisplayName("profile 의 key 를 bearer 로 싣고 model options 경로를 부른다")
    void callsModelOptionsPathWithProfileKeyAsBearer() {
        client.readCatalog(baseUrl(), "dad");

        assertThat(paths).containsExactly("/p/dad/api/model/options");
        assertThat(authorizations).containsExactly("Bearer " + KEY);
    }

    @Test
    @DisplayName("인증된 provider 가 하나도 없으면 빈 목록이다")
    void returnsEmptyListWhenNoProviderIsAuthenticated() {
        respondWith(200, "{\"provider\": \"x\", \"model\": \"y\", \"providers\": []}");

        assertThat(client.readCatalog(baseUrl(), "dad").providers()).isEmpty();
    }

    @Test
    @DisplayName("서버 오류와 429 는 모두 HERMES UNAVAILABLE 이다")
    void serverErrorAnd429AreBothHermesUnavailable() {
        for (int failure : new int[] {500, 503, 429}) {
            respondWith(failure, "{}");

            assertThatThrownBy(() -> client.readCatalog(baseUrl(), "dad"))
                    .as("응답 코드 %d", failure)
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
        }
    }

    @Test
    @DisplayName("key 가 없는 profile 은 그 예외를 그대로 올린다")
    void rethrowsAsIsForProfileWithoutKey() {
        assertThatThrownBy(() -> client.readCatalog(baseUrl(), "kid"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_PROFILE_KEY_MISSING));
        assertThat(paths).isEmpty();
    }

    private void respondWith(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }
}
