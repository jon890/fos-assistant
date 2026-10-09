package com.bifos.assistant.browser.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 중계 설정 두 개의 확인을 본다. 규칙은 {@code docs/features/user-browser.md} 의 「설정(사용자 브라우저)」 이다. */
class BrowserPropertiesTest {

    private static final String BASE = "https://control-plane.example.test/internal/browser-gateway";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("중계 두 값이 모두 있으면 중계가 켜진다")
    void enablesGatewayWithBothValues() {
        assertThat(properties(BASE, SECRET).gatewayEnabled()).isTrue();
        assertThat(properties("http://cp.example.test:8080/internal/browser-gateway", SECRET)
                        .gatewayEnabled())
                .isTrue();
    }

    @Test
    @DisplayName("중계 두 값이 비거나 하나만 있으면 기동하되 중계는 꺼진다")
    void keepsGatewayOffWhenEitherValueIsBlank() {
        assertThat(properties(null, null).gatewayEnabled()).isFalse();
        assertThat(properties("", " ").gatewayEnabled()).isFalse();
        assertThat(properties(BASE, null).gatewayEnabled()).isFalse();
        assertThat(properties(null, SECRET).gatewayEnabled()).isFalse();
    }

    @Test
    @DisplayName("주소가 /internal/browser-gateway 로 끝나지 않거나 http 가 아니면 기동을 멈추고 값을 메시지에 싣지 않는다")
    void rejectsGatewayUrlWithWrongShape() {
        for (String url : new String[] {
            "https://control-plane.example.test/internal/browser-gateway/",
            "https://control-plane.example.test/internal/other",
            "ftp://control-plane.example.test/internal/browser-gateway"
        }) {
            assertThatThrownBy(() -> properties(url, SECRET))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("gateway-base-url")
                    .hasMessageNotContaining("control-plane.example.test");
        }
    }

    @Test
    @DisplayName("표식을 붙인 주소가 대시보드의 검사를 지나지 못하면 기동을 멈추고 값을 메시지에 싣지 않는다")
    void rejectsGatewayUrlTheDashboardWouldRefuse() {
        for (String url : new String[] {
            "https://control_plane.example.test/internal/browser-gateway",
            "https://control-plane.example.test/a%20b/internal/browser-gateway"
        }) {
            assertThatThrownBy(() -> properties(url, SECRET))
                    .as(url)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("gateway-base-url")
                    .hasMessageNotContaining("example.test");
        }
    }

    @Test
    @DisplayName("비밀이 31자면 기동을 멈추고 값을 메시지에 싣지 않으며 32자면 받는다")
    void requiresSecretOfAtLeast32Characters() {
        String shortSecret = SECRET.substring(1);

        assertThatThrownBy(() -> properties(BASE, shortSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway-secret")
                .hasMessageNotContaining(shortSecret);
        assertThat(properties(BASE, SECRET).gatewaySecret()).hasSize(32);
    }

    private static BrowserProperties properties(String gatewayBaseUrl, String gatewaySecret) {
        return new BrowserProperties(
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
                gatewaySecret);
    }

    @Test
    @DisplayName("중계 주소의 모양 검사는 대시보드 plugin 의 정규식과 같은 글이다")
    void matchesDashboardOwnerBrowserPattern() throws IOException {
        String schema = Files.readString(Path.of("../hermes/plugins/dashboard-profile-api/connector_schema.py"));
        Matcher declared = Pattern.compile("OWNER_BROWSER_VALUE_RE = re\\.compile\\(r\"([^\"]+)\"\\)")
                .matcher(schema);

        assertThat(declared.find()).as("대시보드에 OWNER_BROWSER_VALUE_RE 가 있어야 한다").isTrue();
        assertThat(BrowserProperties.OWNER_BROWSER_VALUE.pattern()).isEqualTo(declared.group(1));
    }
}
