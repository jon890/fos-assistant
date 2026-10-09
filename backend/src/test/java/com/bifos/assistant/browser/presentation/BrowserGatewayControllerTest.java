package com.bifos.assistant.browser.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.browser.application.BrowserGateway;
import com.bifos.assistant.browser.application.model.GatewayTarget;
import com.bifos.assistant.browser.domain.CdpGatewayHttp;
import com.bifos.assistant.browser.domain.CdpReply;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 브라우저 중계의 HTTP 창구가 돌려주는 상태 코드와 응답을 본다. 계약은 {@code backend/docs/flow.md} 의 「받는 것」 이다.
 *
 * <p>표식 판정은 대역 {@link BrowserGateway} 가, Chrome 은 경로마다 정한 응답을 주는 대역이 맡는다.
 */
class BrowserGatewayControllerTest {

    private static final String TOKEN = "b1." + "0".repeat(64);
    private static final String BASE = "/internal/browser-gateway/" + TOKEN;
    private static final String RELAY = "ws://control-plane.example.test/internal/browser-gateway/" + TOKEN;
    private static final URI CDP = URI.create("http://192.0.2.10:9999");
    private static final GatewayTarget TARGET = new GatewayTarget(7L, 70L, CDP);

    private final BrowserGateway gateway = mock(BrowserGateway.class);
    private final FakeChrome chrome = new FakeChrome();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(gateway.open(TOKEN)).thenReturn(TARGET);
        when(gateway.relayBase(TOKEN)).thenReturn(Optional.of(RELAY));
        mvc = MockMvcBuilders.standaloneSetup(new BrowserGatewayController(gateway, chrome))
                .build();
    }

    @Test
    @DisplayName("json/version 의 브라우저 WebSocket 주소를 중계 주소로 바꾸고 활동을 기록한다")
    void rewritesVersion() throws Exception {
        chrome.answer("GET /json/version", 200, """
                {"Browser":"Chrome/140.0.0.0",
                 "webSocketDebuggerUrl":"ws://192.0.2.10:9999/devtools/browser/0a1b2c3d-1111-2222-3333-444455556666"}""");

        mvc.perform(get(BASE + "/json/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Browser").value("Chrome/140.0.0.0"))
                .andExpect(jsonPath("$.webSocketDebuggerUrl")
                        .value(RELAY + "/devtools/browser/0a1b2c3d-1111-2222-3333-444455556666"));

        assertThat(chrome.calls).containsExactly(CDP + " GET /json/version");
        verify(gateway).touch(TARGET);
    }

    @Test
    @DisplayName("json 과 json/list 는 줄마다 주소를 바꾸고 DevTools 화면 주소를 뺀다")
    void rewritesEveryListRow() throws Exception {
        chrome.answer("GET /json/list", 200, """
                [{"id":"PAGE1","type":"page","webSocketDebuggerUrl":"ws://192.0.2.10:9999/devtools/page/PAGE1",
                  "devtoolsFrontendUrl":"/devtools/inspector.html?ws=192.0.2.10:9999/devtools/page/PAGE1"},
                 {"id":"PAGE2","type":"page","webSocketDebuggerUrl":"ws://192.0.2.10:9999/devtools/page/PAGE2"}]""");

        for (String path : List.of("/json", "/json/list")) {
            mvc.perform(get(BASE + path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].webSocketDebuggerUrl").value(RELAY + "/devtools/page/PAGE1"))
                    .andExpect(jsonPath("$[0].devtoolsFrontendUrl").doesNotExist())
                    .andExpect(jsonPath("$[1].webSocketDebuggerUrl").value(RELAY + "/devtools/page/PAGE2"));
        }
    }

    @Test
    @DisplayName("Origin 머리가 있으면 표식을 보기 전에 빈 본문의 403 이다")
    void rejectsBrowserPages() throws Exception {
        for (String path : List.of("/json/version", "/json/list", "/json/close/PAGE1", "/json/protocol")) {
            mvc.perform(get(BASE + path).header("Origin", "https://evil.example.test"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().bytes(new byte[0]));
        }
        mvc.perform(put(URI.create(BASE + "/json/new?about:blank")).header("Origin", "https://evil.example.test"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(gateway);
        assertThat(chrome.calls).isEmpty();
    }

    @Test
    @DisplayName("Sec-Fetch-Site 나 Sec-Fetch-Mode 머리가 있으면 Origin 이 없어도 표식을 보기 전에 빈 본문의 403 이다")
    void rejectsBrowserFetchMetadata() throws Exception {
        for (String name : List.of("Sec-Fetch-Site", "Sec-Fetch-Mode")) {
            String value = "Sec-Fetch-Site".equals(name) ? "cross-site" : "no-cors";
            for (String path : List.of(
                    "/json/version", "/json/list", "/json/close/PAGE1", "/json/activate/PAGE1", "/json/protocol")) {
                mvc.perform(get(BASE + path).header(name, value))
                        .andExpect(status().isForbidden())
                        .andExpect(content().bytes(new byte[0]));
            }
            mvc.perform(put(URI.create(BASE + "/json/new?about:blank")).header(name, value))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(gateway);
        assertThat(chrome.calls).isEmpty();
    }

    @Test
    @DisplayName("활동을 기록하지 못해도 Chrome 의 답을 그대로 준다")
    void answersEvenWhenTouchFails() throws Exception {
        chrome.answer("GET /json/activate/PAGE1", 200, "Target activated");
        doThrow(new IllegalStateException("database is unavailable"))
                .when(gateway)
                .touch(TARGET);

        mvc.perform(get(BASE + "/json/activate/PAGE1"))
                .andExpect(status().isOk())
                .andExpect(content().string("Target activated"));

        assertThat(chrome.calls).containsExactly(CDP + " GET /json/activate/PAGE1");
    }

    @Test
    @DisplayName("판정의 오류 코드를 정한 상태로 바꾸고 본문은 비운다")
    void mapsRefusalsToStatus() throws Exception {
        Map<ErrorCode, Integer> expected = Map.of(
                ErrorCode.BROWSER_NOT_FOUND, 404,
                ErrorCode.BROWSER_DISABLED, 503,
                ErrorCode.BROWSER_CAPACITY, 503,
                ErrorCode.BROWSER_BUSY, 503,
                ErrorCode.BROWSER_START_FAILED, 502,
                ErrorCode.BROWSER_STOP_FAILED, 502);

        for (Map.Entry<ErrorCode, Integer> entry : expected.entrySet()) {
            doThrow(new ApiException(entry.getKey(), "refused")).when(gateway).open(TOKEN);

            MvcResult result = mvc.perform(get(BASE + "/json/version")).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("오류 코드 %s", entry.getKey())
                    .isEqualTo(entry.getValue());
            assertThat(result.getResponse().getContentAsByteArray())
                    .as("오류 코드 %s 의 본문", entry.getKey())
                    .isEmpty();
        }
        assertThat(chrome.calls).isEmpty();
    }

    @Test
    @DisplayName("Chrome 에 닿지 못하면 빈 본문의 502 이고 활동을 기록하지 않는다")
    void answersBadGatewayWhenChromeIsUnreachable() throws Exception {
        mvc.perform(get(BASE + "/json/version"))
                .andExpect(status().isBadGateway())
                .andExpect(content().bytes(new byte[0]));

        verify(gateway, never()).touch(any());
    }

    @Test
    @DisplayName("json/new 는 풀어 읽은 주소가 http, https, about:blank 일 때만 원문 쿼리 그대로 넘기고 아니면 400 이다")
    void opensOnlyWebAddresses() throws Exception {
        chrome.answer("PUT /json/new?https%3A%2F%2Fexample.com%2Fa%3Fb%3D1", 200, """
                {"id":"NEW1","type":"page","webSocketDebuggerUrl":"ws://192.0.2.10:9999/devtools/page/NEW1",
                 "devtoolsFrontendUrl":"/devtools/inspector.html"}""");
        chrome.answer("PUT /json/new?about:blank", 200, """
                {"id":"NEW2","type":"page","webSocketDebuggerUrl":"ws://192.0.2.10:9999/devtools/page/NEW2"}""");

        mvc.perform(put(URI.create(BASE + "/json/new?https%3A%2F%2Fexample.com%2Fa%3Fb%3D1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("NEW1"))
                .andExpect(jsonPath("$.webSocketDebuggerUrl").value(RELAY + "/devtools/page/NEW1"))
                .andExpect(jsonPath("$.devtoolsFrontendUrl").doesNotExist());
        mvc.perform(put(URI.create(BASE + "/json/new?about:blank")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("NEW2"));
        for (String query : List.of("file:///etc/passwd", "javascript:alert(1)", "chrome://settings", "")) {
            mvc.perform(put(URI.create(BASE + "/json/new?" + query)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().bytes(new byte[0]));
        }
        mvc.perform(put(URI.create(BASE + "/json/new"))).andExpect(status().isBadRequest());

        assertThat(chrome.calls)
                .containsExactly(
                        CDP + " PUT /json/new?https%3A%2F%2Fexample.com%2Fa%3Fb%3D1",
                        CDP + " PUT /json/new?about:blank");
    }

    @Test
    @DisplayName("json/close 와 json/activate 는 영문자와 숫자 번호만 넘기고 Chrome 의 글을 그대로 준다")
    void forwardsTabCommandsWithPlainIds() throws Exception {
        chrome.answer("GET /json/close/PAGE1", 200, "Target is closing");
        chrome.answer("GET /json/activate/PAGE1", 200, "Target activated");

        mvc.perform(get(BASE + "/json/close/PAGE1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain; charset=UTF-8"))
                .andExpect(content().string("Target is closing"));
        mvc.perform(get(BASE + "/json/activate/PAGE1"))
                .andExpect(status().isOk())
                .andExpect(content().string("Target activated"));
        mvc.perform(get(BASE + "/json/close/a.b"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]));
        mvc.perform(get(BASE + "/json/activate/a-b")).andExpect(status().isNotFound());

        assertThat(chrome.calls).containsExactly(CDP + " GET /json/close/PAGE1", CDP + " GET /json/activate/PAGE1");
    }

    @Test
    @DisplayName("받기로 하지 않은 경로와 메서드는 빈 본문의 404 이다")
    void answersNotFoundForOtherPaths() throws Exception {
        mvc.perform(get(BASE + "/json/protocol"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]));
        mvc.perform(post(BASE + "/json/version"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]));
        mvc.perform(get(BASE + "/devtools/page/PAGE1"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]));

        verifyNoInteractions(gateway);
        assertThat(chrome.calls).isEmpty();
    }

    @Test
    @DisplayName("Upgrade 머리가 있는 요청은 받기 매핑에 걸리지 않아 WebSocket 처리기로 갈 수 있다")
    void leavesUpgradeRequestsToWebSocketHandlers() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/devtools/page/PAGE1").header("Upgrade", "websocket"))
                .andReturn();

        assertThat(result.getHandler()).isNull();
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("WebSocket 이 아닌 Upgrade 머리가 실린 요청은 받기 매핑이 빈 404 로 받는다")
    void answersNotFoundForOtherUpgrades() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/json/protocol").header("Upgrade", "h2c"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]))
                .andReturn();

        assertThat(result.getHandler()).isNotNull();
    }

    /** 경로마다 정한 응답을 주는 Chrome 대역이다. 정하지 않은 경로는 닿지 못한 것처럼 실패한다. */
    private static final class FakeChrome implements CdpGatewayHttp {

        private final Map<String, CdpReply> replies = new ConcurrentHashMap<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();

        void answer(String request, int status, String body) {
            String contentType = body.startsWith("{") || body.startsWith("[")
                    ? "application/json; charset=UTF-8"
                    : "text/plain; charset=UTF-8";
            replies.put(request, new CdpReply(status, contentType, body.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public CdpReply get(URI cdp, String path) {
            return reply(cdp, "GET " + path);
        }

        @Override
        public CdpReply put(URI cdp, String pathAndQuery) {
            return reply(cdp, "PUT " + pathAndQuery);
        }

        private CdpReply reply(URI cdp, String request) {
            calls.add(cdp + " " + request);
            CdpReply reply = replies.get(request);
            if (reply == null) {
                throw new IllegalStateException("cdp gateway call failed");
            }
            return reply;
        }
    }
}
