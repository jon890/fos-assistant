package com.bifos.assistant.browser.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.application.BrowserGateway;
import com.bifos.assistant.browser.application.model.GatewayTarget;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

/**
 * 브라우저 중계의 WebSocket handshake 판정이 handshake 모양을 브라우저를 켜기 전에 보는지 확인한다. 계약은
 * {@code backend/docs/flow.md} 의 「WebSocket」 이다.
 */
class BrowserGatewayHandshakeTest {

    private static final String TOKEN = "u7.1999999999.sig";
    private static final String PATH =
            "/internal/browser-gateway/" + TOKEN + "/devtools/browser/0a1b2c3d-1111-2222-3333-444455556666";
    private static final GatewayTarget TARGET = new GatewayTarget(7L, 70L, URI.create("http://192.0.2.10:9999"));

    private final BrowserGateway gateway = mock(BrowserGateway.class);
    private final BrowserGatewayHandshake handshake = new BrowserGatewayHandshake(gateway);

    @Test
    @DisplayName("GET 이 아닌 upgrade 요청은 브라우저를 켜지 않고 빈 400 으로 거절한다")
    void refusesNonGetWithoutOpening() throws Exception {
        MockHttpServletRequest request = upgrade("POST", "websocket");

        Result result = judge(request);

        assertThat(result.accepted()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(400);
        assertThat(result.response().getContentAsByteArray()).isEmpty();
        assertThat(result.attributes()).isEmpty();
        verify(gateway, never()).open(anyString());
    }

    @Test
    @DisplayName("Upgrade 머리가 없거나 websocket 이 아니면 브라우저를 켜지 않고 빈 400 으로 거절한다")
    void refusesMissingOrOtherUpgradeWithoutOpening() throws Exception {
        for (String upgrade : new String[] {null, "h2c"}) {
            Result result = judge(upgrade("GET", upgrade));

            assertThat(result.accepted()).as("Upgrade %s", upgrade).isFalse();
            assertThat(result.response().getStatus()).as("Upgrade %s", upgrade).isEqualTo(400);
            assertThat(result.response().getContentAsByteArray()).isEmpty();
        }
        verify(gateway, never()).open(anyString());
    }

    @Test
    @DisplayName("Upgrade 값은 대소문자를 가리지 않고 websocket 이면 판정을 이어 대상을 세션 속성에 둔다")
    void acceptsWebSocketUpgradeIgnoringCase() throws Exception {
        when(gateway.open(TOKEN)).thenReturn(TARGET);

        Result result = judge(upgrade("GET", "WEBSOCKET"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.attributes())
                .containsEntry(BrowserGatewayHandshake.TARGET, TARGET)
                .containsEntry(BrowserGatewayHandshake.KIND, "browser");
        verify(gateway).open(TOKEN);
    }

    private static MockHttpServletRequest upgrade(String method, String upgrade) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, PATH);
        if (upgrade != null) {
            request.addHeader("Upgrade", upgrade);
            request.addHeader("Connection", "Upgrade");
        }
        return request;
    }

    private Result judge(MockHttpServletRequest request) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = handshake.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response),
                mock(WebSocketHandler.class),
                attributes);
        return new Result(accepted, response, attributes);
    }

    private record Result(boolean accepted, MockHttpServletResponse response, Map<String, Object> attributes) {}
}
