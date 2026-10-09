package com.bifos.assistant.browser.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.bifos.assistant.browser.application.BrowserGatewayTokens;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.EchoCdpRelayConnector;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 서버에서 브라우저 중계의 WebSocket 이 handshake 를 판정하고 조각을 잇는지 본다.
 *
 * <p>Chrome 쪽은 공통 대역 {@link EchoCdpRelayConnector} 가 받은 조각을 그대로 되돌려 준다. 표식은 허용된 사용자의 호출 표식이다.
 */
@BackendIntegrationTest
@OverrideProperties({
    "assistant.browser.enabled=true",
    "assistant.browser.gateway-base-url=http://control-plane.example.test/internal/browser-gateway",
    "assistant.browser.gateway-secret=0123456789abcdef0123456789abcdef-test"
})
class BrowserGatewaySocketIntegrationTest {

    private static final String EMAIL = "browser-gateway-socket@example.com";
    private static final String BROWSER_ID = "0a1b2c3d-1111-2222-3333-444455556666";
    private static final Duration WAIT = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    @Autowired
    BrowserGatewayTokens tokens;

    @Autowired
    EchoCdpRelayConnector chrome;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private String token;

    @BeforeEach
    void setUp() {
        clean();
        people.save(AllowedPerson.of(EMAIL, "x", "browser-gateway-socket", Instant.now()));
        AppUser user = users.save(AppUser.of(EMAIL, "x", 1L, UserRole.MEMBER, Instant.now()));
        String address = tokens.callAddress(user.id()).orElseThrow();
        token = address.substring(address.lastIndexOf('/') + 1);
    }

    @AfterEach
    void tearDown() {
        client.close();
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM user_browser");
        people.findAll().stream().filter(p -> p.email().equals(EMAIL)).forEach(people::delete);
        users.findAllByNormalizedEmail(EMAIL).forEach(users::delete);
    }

    @Test
    @DisplayName("devtools 경로에 붙으면 10만 글자 메시지가 조각째 Chrome 쪽을 지나 온전히 되돌아온다")
    void relaysLargeMessageInFragments() throws Exception {
        Collector collector = new Collector();
        WebSocket socket = client.newWebSocketBuilder()
                .buildAsync(devtools(token), collector)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        String message = "{\"id\":1,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":\"" + "a".repeat(100_000)
                + "\"}}";

        socket.sendText(message, true).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(collector.message.get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(message);
        assertThat(chrome.opened()).isEqualTo(1);
        assertThat(chrome.fragments()).as("Chrome 쪽에 넘긴 조각 수").hasSizeGreaterThan(1);
        assertThat(String.join("", chrome.fragments())).isEqualTo(message);
        socket.abort();
    }

    @Test
    @DisplayName("Origin 을 실은 연결은 403 으로 거절하고 Chrome 쪽에 붙지 않는다")
    void rejectsBrowserPages() {
        int status = refusedStatus(
                client.newWebSocketBuilder().header("Origin", "https://evil.example.test"), devtools(token));

        assertThat(status).isEqualTo(403);
        assertThat(chrome.opened()).isZero();
    }

    @Test
    @DisplayName("틀린 표식은 404 로 거절한다")
    void rejectsWrongToken() {
        String wrong = token.substring(0, token.length() - 1) + (token.endsWith("0") ? "1" : "0");

        int status = refusedStatus(client.newWebSocketBuilder(), devtools(wrong));

        assertThat(status).isEqualTo(404);
        assertThat(chrome.opened()).isZero();
    }

    @Test
    @DisplayName("중계 아래 devtools 가 아닌 경로의 WebSocket 연결은 404 로 거절한다")
    void rejectsOtherSocketPaths() {
        for (String path : new String[] {"/json/version", "/devtools/worker/" + BROWSER_ID, "/devtools/page/a/b"}) {
            int status = refusedStatus(client.newWebSocketBuilder(), relay(token, path));

            assertThat(status).as("경로 %s", path).isEqualTo(404);
        }
        assertThat(chrome.opened()).isZero();
    }

    @Test
    @DisplayName("같은 devtools 경로의 일반 GET 은 빈 본문의 404 이다")
    void answersEmptyNotFoundForPlainGet() throws Exception {
        URI address = URI.create(
                "http://127.0.0.1:" + port + "/internal/browser-gateway/" + token + "/devtools/browser/" + BROWSER_ID);

        HttpResponse<String> response =
                client.send(HttpRequest.newBuilder(address).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
        assertThat(chrome.opened()).isZero();
    }

    private URI devtools(String gatewayToken) {
        return relay(gatewayToken, "/devtools/browser/" + BROWSER_ID);
    }

    private URI relay(String gatewayToken, String path) {
        return URI.create("ws://localhost:" + port + "/internal/browser-gateway/" + gatewayToken + path);
    }

    /** handshake 가 거절되기를 기다려 그 상태 코드를 준다. 열리면 실패다. */
    private static int refusedStatus(WebSocket.Builder builder, URI address) {
        Throwable failure = catchThrowable(() -> builder.buildAsync(address, new Collector())
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .abort());
        assertThat(failure).as("handshake 거절").isInstanceOf(ExecutionException.class);
        Throwable cause =
                failure.getCause() instanceof CompletionException wrapped ? wrapped.getCause() : failure.getCause();
        assertThat(cause).isInstanceOf(WebSocketHandshakeException.class);
        return ((WebSocketHandshakeException) cause).getResponse().statusCode();
    }

    /** 받은 조각을 모아 첫 메시지를 준다. */
    private static final class Collector implements WebSocket.Listener {

        final CompletableFuture<String> message = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                message.complete(buffer.toString());
            }
            webSocket.request(1);
            return null;
        }
    }
}
