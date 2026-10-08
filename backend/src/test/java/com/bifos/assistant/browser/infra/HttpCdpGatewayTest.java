package com.bifos.assistant.browser.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.browser.domain.CdpReply;
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

class HttpCdpGatewayTest {

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpCdpGateway gateway = new HttpCdpGateway();
    private HttpServer server;
    private URI cdp;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        cdp = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        gateway.close();
        server.stop(0);
    }

    @Test
    @DisplayName("Chrome 의 상태 코드와 Content-Type 과 본문을 그대로 돌려주고 Origin 을 보내지 않는다")
    void returnsChromeReplyAsIs() {
        serve("/json/close", 404, "text/plain", "No such target id: X".getBytes(StandardCharsets.UTF_8));

        CdpReply reply = gateway.get(cdp, "/json/close/X");

        assertThat(reply.status()).isEqualTo(404);
        assertThat(reply.contentType()).isEqualTo("text/plain");
        assertThat(new String(reply.body(), StandardCharsets.UTF_8)).isEqualTo("No such target id: X");
        assertThat(requests).containsExactly("GET /json/close/X origin=null");
    }

    @Test
    @DisplayName("PUT 은 받은 원문 쿼리를 바꾸지 않고 보낸다")
    void putsRawQuery() {
        serve("/json/new", 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));

        gateway.put(cdp, "/json/new?https%3A%2F%2Fexample.com%2Fa%3Fb%3D1");

        assertThat(requests).containsExactly("PUT /json/new?https%3A%2F%2Fexample.com%2Fa%3Fb%3D1 origin=null");
    }

    @Test
    @DisplayName("본문은 1MB 까지 읽고 넘으면 주소와 본문을 싣지 않고 실패한다")
    void limitsBodySize() {
        serve("/json/list", 200, "application/json", new byte[HttpCdpGateway.MAX_BODY_BYTES]);
        serve(
                "/json/version",
                200,
                "application/json",
                "x".repeat(HttpCdpGateway.MAX_BODY_BYTES + 1).getBytes(StandardCharsets.UTF_8));

        assertThat(gateway.get(cdp, "/json/list").body()).hasSize(HttpCdpGateway.MAX_BODY_BYTES);
        assertThatThrownBy(() -> gateway.get(cdp, "/json/version"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("127.0.0.1")
                .hasMessageNotContaining("xxx");
    }

    @Test
    @DisplayName("닿지 못하면 주소를 싣지 않고 실패한다")
    void failsWithoutAddressWhenUnreachable() {
        // 1번 포트는 받는 곳이 없어 연결이 바로 거절된다
        URI closed = URI.create("http://127.0.0.1:1");

        assertThatThrownBy(() -> gateway.get(closed, "/json/version"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("127.0.0.1");
    }

    private void serve(String context, int status, String contentType, byte[] body) {
        server.createContext(context, exchange -> answer(exchange, status, contentType, body));
    }

    private void answer(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        URI uri = exchange.getRequestURI();
        requests.add(exchange.getRequestMethod() + " " + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                + " origin=" + exchange.getRequestHeaders().getFirst("Origin"));
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
