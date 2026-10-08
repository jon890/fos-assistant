package com.bifos.assistant.hermes;

import static com.bifos.assistant.hermes.dto.ReasoningCapability.Support.SUPPORTED;
import static com.bifos.assistant.hermes.dto.ReasoningCapability.Support.UNKNOWN;
import static com.bifos.assistant.hermes.dto.ReasoningCapability.Support.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.dto.ReasoningCapability;
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
                .isEqualTo(Map.of(
                        "example-model", new ReasoningCapability(SUPPORTED, UNKNOWN),
                        "example-model-mini", new ReasoningCapability(UNSUPPORTED, UNKNOWN)));
    }

    @Test
    @DisplayName("capabilities 에 칸이 없는 모델도 표에 넣고 UNKNOWN 으로 둔다")
    void keepsModelsMissingFromCapabilitiesAsUnknown() {
        respondWith(200, """
                {"providers": [{"slug": "p", "authenticated": true, "models": ["a", "b"],
                 "capabilities": {"a": {"reasoning": true}, "b": {}}}]}
                """);

        HermesModelCatalog catalog = client.readCatalog(baseUrl(), "dad");

        assertThat(catalog.defaultProvider()).isNull();
        assertThat(catalog.defaultModel()).isNull();
        assertThat(catalog.providers().get(0).name()).isEqualTo("p");
        assertThat(catalog.providers().get(0).reasoning())
                .isEqualTo(
                        Map.of("a", new ReasoningCapability(SUPPORTED, UNKNOWN), "b", ReasoningCapability.UNKNOWN_ALL));
    }

    @Test
    @DisplayName("reasoning 이 거짓인 모델은 UNSUPPORTED 이고 칸이 없거나 capabilities 가 없는 모델은 UNKNOWN 이다")
    void distinguishesExplicitFalseFromMissingReasoning() {
        respondWith(200, """
                {"providers": [
                  {"slug": "described", "authenticated": true, "models": ["on", "off", "silent", "odd"],
                   "capabilities": {"on": {"reasoning": true}, "off": {"reasoning": false},
                                    "silent": {}, "odd": {"reasoning": "yes"}}},
                  {"slug": "bare", "authenticated": true, "models": ["x"]}
                ]}
                """);

        HermesModelCatalog catalog = client.readCatalog(baseUrl(), "dad");

        Map<String, ReasoningCapability> described = catalog.providers().get(0).reasoning();
        assertThat(described.get("on").support()).isEqualTo(SUPPORTED);
        assertThat(described.get("off").support()).isEqualTo(UNSUPPORTED);
        assertThat(described.get("silent").support()).isEqualTo(UNKNOWN);
        assertThat(described.get("odd").support()).isEqualTo(UNKNOWN);
        assertThat(catalog.providers().get(1).reasoning()).isEqualTo(Map.of("x", ReasoningCapability.UNKNOWN_ALL));
    }

    @Test
    @DisplayName("can_disable_reasoning 이 참이면 SUPPORTED, 거짓이면 UNSUPPORTED, 없으면 UNKNOWN 이다")
    void mapsCanDisableReasoningToDisableSupport() {
        respondWith(200, """
                {"providers": [{"slug": "aggregator", "authenticated": true, "models": ["yes", "no", "unsaid"],
                 "capabilities": {
                   "yes": {"reasoning": true, "can_disable_reasoning": true},
                   "no": {"reasoning": true, "can_disable_reasoning": false},
                   "unsaid": {"reasoning": true}}}]}
                """);

        Map<String, ReasoningCapability> reasoning =
                client.readCatalog(baseUrl(), "dad").providers().get(0).reasoning();

        assertThat(reasoning.get("yes")).isEqualTo(new ReasoningCapability(SUPPORTED, SUPPORTED));
        assertThat(reasoning.get("no")).isEqualTo(new ReasoningCapability(SUPPORTED, UNSUPPORTED));
        assertThat(reasoning.get("unsaid")).isEqualTo(new ReasoningCapability(SUPPORTED, UNKNOWN));
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
                            ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
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

    @Test
    @DisplayName("같은 모델 이름도 provider 별로 reasoning 지원을 읽고 이름으로 추정하지 않는다")
    void keepsCapabilitiesSeparateForSameModelAcrossProviders() {
        respondWith(200, """
                {"providers": [
                  {"slug": "openai-codex", "authenticated": true, "models": ["shared-model"],
                   "capabilities": {"shared-model": {"reasoning": false, "can_disable_reasoning": true}}},
                  {"slug": "anthropic", "authenticated": true, "models": ["shared-model"],
                   "capabilities": {"shared-model": {"reasoning": true, "can_disable_reasoning": false}}},
                  {"slug": "openrouter", "authenticated": true, "models": ["shared-model"]}
                ]}
                """);

        HermesModelCatalog catalog = client.readCatalog(baseUrl(), "dad");

        assertThat(catalog.providers()).extracting(HermesModelCatalog.Provider::slug)
                .containsExactly("openai-codex", "anthropic", "openrouter");
        assertThat(catalog.providers()).extracting(provider -> provider.reasoning().get("shared-model"))
                .containsExactly(
                        new ReasoningCapability(UNSUPPORTED, SUPPORTED),
                        new ReasoningCapability(SUPPORTED, UNSUPPORTED),
                        ReasoningCapability.UNKNOWN_ALL);
    }

    private void respondWith(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }
}
