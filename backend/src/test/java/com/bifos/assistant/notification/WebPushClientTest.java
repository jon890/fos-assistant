package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.notification.config.PushProperties;
import com.bifos.assistant.notification.infra.HttpWebPushClient;
import com.bifos.assistant.notification.infra.PushResponse;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 웹 푸시 HTTP 클라이언트가 머리와 본문을 그대로 보내고 응답을 읽는지 본다(ADR-20261120 / web-push).
 *
 * <p>클라이언트는 주소 정책을 보지 않으므로 검사 안의 http 서버로 부른다. 정책은 {@code PushEndpointPolicyTest} 가 본다.
 */
class WebPushClientTest {

    private final AtomicReference<Headers> received = new AtomicReference<>();
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private HttpServer server;
    private HttpWebPushClient client;


    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            received.set(exchange.getRequestHeaders());
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/busy", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Retry-After", "7");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.createContext("/moved", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        client = new HttpWebPushClient(new PushProperties(List.of(),
            Duration.ofSeconds(2), Duration.ofSeconds(2), List.of(), Duration.ofSeconds(30), 10));
    }


    @AfterEach
    void tearDown() {
        server.stop(0);
    }


    @Test
    @DisplayName("머리와 본문을 그대로 보내고 201 을 돌려받는다")
    void sendsHeadersAndBody() {
        PushResponse response = client.send(url("/ok"), "vapid t=x, k=y", new byte[] {1, 2, 3});

        assertThat(response.delivered()).isTrue();
        assertThat(received.get().getFirst("Authorization")).isEqualTo("vapid t=x, k=y");
        assertThat(received.get().getFirst("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(received.get().getFirst("TTL")).isEqualTo("86400");
        assertThat(received.get().getFirst("Urgency")).isEqualTo("normal");
        assertThat(receivedBody.get()).containsExactly(1, 2, 3);
    }


    @Test
    @DisplayName("Retry-After 를 읽고, redirect 는 따르지 않고, 연결하지 못하면 상태 0 이다")
    void readsRetryAfterAndDoesNotFollowRedirects() {
        PushResponse busy = client.send(url("/busy"), "a", new byte[0]);
        assertThat(busy.status()).isEqualTo(429);
        assertThat(busy.retryAfter()).isEqualTo(Duration.ofSeconds(7));

        PushResponse moved = client.send(url("/moved"), "a", new byte[0]);
        assertThat(moved.status()).isEqualTo(302);
        assertThat(received.get()).as("넘긴 곳으로 가지 않는다").isNull();

        int port = server.getAddress().getPort();
        server.stop(0);
        assertThat(client.send("http://127.0.0.1:" + port + "/ok", "a", new byte[0]).status()).isZero();
    }


    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
