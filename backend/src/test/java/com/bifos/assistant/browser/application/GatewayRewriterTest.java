package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CDP 대상 한 줄을 중계 주소로 바꾸는 규칙을 본다. 규칙은 {@code backend/docs/flow.md} 의 「받는 것」 이다. */
class GatewayRewriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RELAY = "ws://control-plane.example.test/internal/browser-gateway/b1.sig";

    @Test
    @DisplayName("페이지 대상의 WebSocket 주소는 중계 주소로 바뀌고 DevTools 화면 주소 두 칸은 빠진다")
    void rewritesPageTarget() {
        JsonNode target = json("""
                {"id":"PAGE1","type":"page","url":"https://example.com/",
                 "webSocketDebuggerUrl":"ws://192.0.2.10:9999/devtools/page/PAGE1",
                 "devtoolsFrontendUrl":"/devtools/inspector.html?ws=192.0.2.10:9999/devtools/page/PAGE1",
                 "devtoolsFrontendUrlCompat":"/devtools/inspector.html?ws=192.0.2.10:9999/devtools/page/PAGE1"}""");

        JsonNode rewritten = GatewayRewriter.rewrite(target, RELAY);

        assertThat(rewritten.path("webSocketDebuggerUrl").asString()).isEqualTo(RELAY + "/devtools/page/PAGE1");
        assertThat(rewritten.has("devtoolsFrontendUrl")).isFalse();
        assertThat(rewritten.has("devtoolsFrontendUrlCompat")).isFalse();
        assertThat(rewritten.path("id").asString()).isEqualTo("PAGE1");
        assertThat(rewritten.path("url").asString()).isEqualTo("https://example.com/");
        assertThat(target.has("devtoolsFrontendUrl")).as("원본은 바꾸지 않는다").isTrue();
    }

    @Test
    @DisplayName("GUID 모양의 브라우저 대상 번호도 중계 주소로 바뀐다")
    void rewritesBrowserTargetWithGuid() {
        JsonNode version = json("""
                {"Browser":"Chrome/140.0.0.0",
                 "webSocketDebuggerUrl":"ws://192.0.2.10:9999/devtools/browser/0a1b2c3d-1111-2222-3333-444455556666"}""");

        JsonNode rewritten = GatewayRewriter.rewrite(version, RELAY);

        assertThat(rewritten.path("webSocketDebuggerUrl").asString())
                .isEqualTo(RELAY + "/devtools/browser/0a1b2c3d-1111-2222-3333-444455556666");
        assertThat(rewritten.path("Browser").asString()).isEqualTo("Chrome/140.0.0.0");
    }

    @Test
    @DisplayName("경로 모양이 다르면 WebSocket 주소 칸을 뺀다")
    void dropsUnexpectedSocketPaths() {
        for (String address : new String[] {
            "ws://192.0.2.10:9999/devtools/page/../browser/X",
            "ws://192.0.2.10:9999/devtools/worker/W1",
            "ws://192.0.2.10:9999/devtools/page/a.b",
            "ws://192.0.2.10:9999/devtools/page/" + "a".repeat(129),
            "not a url"
        }) {
            JsonNode target = JSON.createObjectNode().put("id", "X").put("webSocketDebuggerUrl", address);

            JsonNode rewritten = GatewayRewriter.rewrite(target, RELAY);

            assertThat(rewritten.has("webSocketDebuggerUrl"))
                    .as("주소 %s 는 빠져야 한다", address)
                    .isFalse();
            assertThat(rewritten.path("id").asString()).isEqualTo("X");
        }
    }

    @Test
    @DisplayName("번호가 128자이면 바꾼다")
    void acceptsLongestTargetId() {
        String id = "a".repeat(128);
        JsonNode target =
                JSON.createObjectNode().put("webSocketDebuggerUrl", "ws://192.0.2.10:9999/devtools/page/" + id);

        assertThat(GatewayRewriter.rewrite(target, RELAY)
                        .path("webSocketDebuggerUrl")
                        .asString())
                .isEqualTo(RELAY + "/devtools/page/" + id);
    }

    @Test
    @DisplayName("https 기반 주소의 중계 주소는 wss 로 시작하고 http 기반은 ws 로 시작한다")
    void followsGatewaySchemeForRelay() {
        JsonNode target = JSON.createObjectNode().put("webSocketDebuggerUrl", "ws://192.0.2.10:9999/devtools/page/P1");
        String secure = relayBase("https://control-plane.example.test/internal/browser-gateway");
        String plain = relayBase("http://control-plane.example.test/internal/browser-gateway");

        assertThat(GatewayRewriter.rewrite(target, secure)
                        .path("webSocketDebuggerUrl")
                        .asString())
                .isEqualTo("wss://control-plane.example.test/internal/browser-gateway/b1.sig/devtools/page/P1");
        assertThat(GatewayRewriter.rewrite(target, plain)
                        .path("webSocketDebuggerUrl")
                        .asString())
                .isEqualTo("ws://control-plane.example.test/internal/browser-gateway/b1.sig/devtools/page/P1");
    }

    private static String relayBase(String gatewayBaseUrl) {
        BrowserProperties properties = new BrowserProperties(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1024,
                null,
                null,
                null,
                2,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                gatewayBaseUrl,
                "0123456789abcdef0123456789abcdef");
        return new BrowserGatewayTokens(LiveProperties.fixed(BrowserProperties.class, properties), Clock.systemUTC())
                .relayBase("b1.sig")
                .orElseThrow();
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
