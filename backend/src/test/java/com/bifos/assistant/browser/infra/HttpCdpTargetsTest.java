package com.bifos.assistant.browser.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.browser.domain.CdpTarget;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpCdpTargetsTest {

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpCdpTargets targets = new HttpCdpTargets();
    private HttpServer server;
    private URI cdp;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/json/list", exchange -> answer(exchange, 200, """
                [
                  {"id":"PAGE1","type":"page","title":"첫 탭","url":"https://example.com/a"},
                  {"id":"SW1","type":"service_worker","title":"sw","url":"https://example.com/sw.js"},
                  {"id":"../x","type":"page","title":"이상한 번호","url":"https://example.com/b"},
                  {"id":"PAGE2","type":"page","title":"둘째 탭","url":"https://example.com/c"}
                ]"""));
        server.createContext("/json/new", exchange -> answer(exchange, 200, """
                {"id":"NEW1","type":"page","title":"","url":"https://example.com/login?a=1&b=2"}"""));
        server.createContext("/json/activate", exchange -> answer(exchange, 200, "Target activated"));
        server.start();
        cdp = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        targets.close();
        server.stop(0);
    }

    @Test
    @DisplayName("탭 목록은 page 이면서 번호가 영문자와 숫자인 대상만 돌려준다")
    void listsOnlyPages() {
        List<CdpTarget> pages = targets.list(cdp);

        assertThat(pages)
                .containsExactly(
                        new CdpTarget("PAGE1", "첫 탭", "https://example.com/a"),
                        new CdpTarget("PAGE2", "둘째 탭", "https://example.com/c"));
        assertThat(requests).containsExactly("GET /json/list");
    }

    @Test
    @DisplayName("새 탭은 PUT 으로 주소를 풀어 읽을 수 있게 담아 연다")
    void createsTabWithPut() {
        CdpTarget created = targets.create(cdp, "https://example.com/login?a=1&b=2");

        assertThat(created.id()).isEqualTo("NEW1");
        assertThat(requests).containsExactly("PUT /json/new?https%3A%2F%2Fexample.com%2Flogin%3Fa%3D1%26b%3D2");
    }

    @Test
    @DisplayName("탭을 앞으로 가져오고 이상한 번호는 부르지 않고 거절한다")
    void activatesAndRejectsMalformedIds() {
        targets.activate(cdp, "PAGE1");

        for (String id : new String[] {"../browser", "a b", "", null}) {
            assertThatThrownBy(() -> targets.activate(cdp, id)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requests).containsExactly("GET /json/activate/PAGE1");
    }

    @Test
    @DisplayName("200 이 아니면 상태 코드만 담아 실패한다")
    void failsOnErrorStatus() {
        server.removeContext("/json/list");
        server.createContext("/json/list", exchange -> answer(exchange, 500, "secret body"));

        assertThatThrownBy(() -> targets.list(cdp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("secret");
    }

    private void answer(HttpExchange exchange, int status, String body) throws IOException {
        requests.add(
                exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                        + (exchange.getRequestURI().getRawQuery() == null
                                ? ""
                                : "?" + exchange.getRequestURI().getRawQuery()));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
