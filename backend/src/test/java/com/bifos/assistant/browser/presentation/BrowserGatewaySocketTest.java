package com.bifos.assistant.browser.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.application.BrowserGateway;
import com.bifos.assistant.browser.application.model.GatewayTarget;
import com.bifos.assistant.browser.domain.CdpRelay;
import com.bifos.assistant.browser.domain.CdpRelayConnector;
import com.bifos.assistant.browser.domain.CdpRelayListener;
import jakarta.websocket.Session;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

/**
 * 브라우저 중계의 WebSocket 처리기를 직접 불러 조각을 잇고 닫는 일을 본다. 계약은 {@code docs/backend/user-browser.md} 의
 * 「WebSocket」 이다.
 *
 * <p>Chrome 쪽은 보낸 조각을 기록하는 대역이, 받은 쪽 세션은 보낸 메시지와 닫힘을 기록하는 대역이 맡는다.
 */
class BrowserGatewaySocketTest {

    private static final URI CDP = URI.create("http://192.0.2.10:9999");
    private static final GatewayTarget TARGET = new GatewayTarget(7L, 70L, CDP);
    private static final String BROWSER_ID = "0a1b2c3d-1111-2222-3333-444455556666";
    private static final long LIMIT = 10;
    private static final Duration IDLE = Duration.ofMinutes(10);

    private final BrowserGateway gateway = mock(BrowserGateway.class);
    private final FakeRelays relays = new FakeRelays();
    private final AtomicInteger handleCloses = new AtomicInteger();
    private final BrowserGatewaySocket socket = new BrowserGatewaySocket(gateway, relays, LIMIT);
    private final List<TextMessage> sent = new ArrayList<>();
    private final AtomicReference<CloseStatus> sessionClosed = new AtomicReference<>();
    private final AtomicInteger sessionCloses = new AtomicInteger();
    private final Session container = mock(Session.class);
    private NativeWebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        when(gateway.hold(TARGET)).thenReturn(handleCloses::incrementAndGet);
        when(gateway.idleTimeout()).thenReturn(IDLE);
        Map<String, Object> attributes = new ConcurrentHashMap<>(Map.of(
                BrowserGatewayHandshake.TARGET, TARGET,
                BrowserGatewayHandshake.KIND, "browser",
                BrowserGatewayHandshake.TARGET_ID, BROWSER_ID));
        session = mock(NativeWebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getNativeSession(Session.class)).thenReturn(container);
        when(session.isOpen()).thenAnswer(invocation -> sessionClosed.get() == null);
        doAnswer(invocation -> sent.add(invocation.getArgument(0)))
                .when(session)
                .sendMessage(any());
        doAnswer(invocation -> {
                    sessionClosed.set(invocation.getArgument(0));
                    sessionCloses.incrementAndGet();
                    return null;
                })
                .when(session)
                .close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("handshake 가 정한 대상에 붙고 양쪽 조각을 그대로 잇는다. 메시지가 끝나면 활동을 기록한다")
    void relaysFragmentsBothWays() throws Exception {
        socket.afterConnectionEstablished(session);

        socket.handleMessage(session, new TextMessage("{\"id\":", false));
        verify(gateway, never()).touch(TARGET);
        socket.handleMessage(session, new TextMessage("1}", true));
        relays.listener.onFragment("{\"id\":1,", false);
        relays.listener.onFragment("\"result\":{}}", true);

        assertThat(relays.opened).isEqualTo(CDP + " browser " + BROWSER_ID);
        assertThat(relays.sent).containsExactly("{\"id\": false", "1} true");
        verify(gateway).touch(TARGET);
        assertThat(sent)
                .extracting(message -> message.getPayload() + " " + message.isLast())
                .containsExactly("{\"id\":1, false", "\"result\":{}} true");
        assertThat(handleCloses).hasValue(0);
        assertThat(socket.supportsPartialMessages()).isTrue();
    }

    @Test
    @DisplayName("받은 쪽이 닫힌 뒤 전송 오류가 또 와도 Chrome 쪽과 핸들은 한 번만 닫고, 이미 닫힌 세션은 다시 닫지 않는다")
    void closesRelayAndHandleOnceWhenSessionClosesFirst() throws Exception {
        socket.afterConnectionEstablished(session);

        socket.afterConnectionClosed(session, CloseStatus.NORMAL);
        socket.handleTransportError(session, new IllegalStateException("late"));

        assertThat(relays.closes).as("Chrome 쪽 닫힘 수").hasValue(1);
        assertThat(handleCloses).as("핸들 닫힘 수").hasValue(1);
        assertThat(sessionCloses).as("세션 닫힘 수").hasValue(0);
    }

    @Test
    @DisplayName("전송 오류 뒤에 받은 쪽이 닫혀도 Chrome 쪽과 핸들, 세션을 한 번씩만 닫는다")
    void closesEverythingOnceWhenTransportErrorComesFirst() throws Exception {
        socket.afterConnectionEstablished(session);

        socket.handleTransportError(session, new IllegalStateException("broken"));
        socket.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        assertThat(relays.closes).as("Chrome 쪽 닫힘 수").hasValue(1);
        assertThat(handleCloses).as("핸들 닫힘 수").hasValue(1);
        assertThat(sessionCloses).as("세션 닫힘 수").hasValue(1);
        assertThat(sessionClosed.get()).isEqualTo(CloseStatus.SERVER_ERROR);
    }

    @Test
    @DisplayName("열리면 컨테이너 세션의 유휴 시간 제한을 브라우저 유휴 시간으로 둔다")
    void limitsIdleTimeOfSession() throws Exception {
        socket.afterConnectionEstablished(session);

        verify(container).setMaxIdleTimeout(IDLE.toMillis());
        assertThat(relays.opened).isNotNull();
    }

    @Test
    @DisplayName("컨테이너 세션을 꺼내지 못해도 Chrome 쪽에 붙는다")
    void connectsWithoutContainerSession() throws Exception {
        when(session.getNativeSession(Session.class)).thenReturn(null);

        socket.afterConnectionEstablished(session);

        assertThat(relays.opened).isEqualTo(CDP + " browser " + BROWSER_ID);
        assertThat(sessionCloses).hasValue(0);
    }

    @Test
    @DisplayName("연결 준비가 실패하면 예외를 던지지 않고 핸들을 닫고 세션을 SERVER_ERROR 로 닫는다")
    void closesHandleAndSessionWhenSetupFails() throws Exception {
        doThrow(new IllegalArgumentException("bad timeout")).when(container).setMaxIdleTimeout(anyLong());

        socket.afterConnectionEstablished(session);

        assertThat(relays.opened).as("Chrome 쪽 연결").isNull();
        assertThat(handleCloses).as("핸들 닫힘 수").hasValue(1);
        assertThat(sessionCloses).as("세션 닫힘 수").hasValue(1);
        assertThat(sessionClosed.get()).isEqualTo(CloseStatus.SERVER_ERROR);
    }

    @Test
    @DisplayName("핸들을 쥐지 못하면 예외를 던지지 않고 세션을 SERVER_ERROR 로 닫는다")
    void closesSessionWhenHoldFails() throws Exception {
        when(gateway.hold(TARGET)).thenThrow(new IllegalStateException("usage failed"));

        socket.afterConnectionEstablished(session);

        assertThat(relays.opened).as("Chrome 쪽 연결").isNull();
        assertThat(sessionCloses).as("세션 닫힘 수").hasValue(1);
        assertThat(sessionClosed.get()).isEqualTo(CloseStatus.SERVER_ERROR);
    }

    @Test
    @DisplayName("Chrome 쪽이 닫히면 받은 쪽을 닫고 핸들을 닫는다")
    void closesSessionWhenChromeCloses() throws Exception {
        socket.afterConnectionEstablished(session);

        relays.listener.onClosed();

        assertThat(sessionClosed.get()).isEqualTo(CloseStatus.GOING_AWAY);
        assertThat(handleCloses).hasValue(1);
    }

    @Test
    @DisplayName("한 메시지의 조각 합이 상한과 같으면 넘기고, 넘으면 넘기지 않고 양쪽을 닫는다")
    void closesBothSidesWhenMessageExceedsLimit() throws Exception {
        socket.afterConnectionEstablished(session);

        socket.handleMessage(session, new TextMessage("12345", false));
        socket.handleMessage(session, new TextMessage("67890", true));
        socket.handleMessage(session, new TextMessage("12345", false));
        socket.handleMessage(session, new TextMessage("678901", false));

        assertThat(relays.sent).containsExactly("12345 false", "67890 true", "12345 false");
        assertThat(relays.closes).hasValue(1);
        assertThat(sessionClosed.get()).isEqualTo(CloseStatus.TOO_BIG_TO_PROCESS);
        assertThat(handleCloses).hasValue(1);
    }

    @Test
    @DisplayName("바이너리 메시지가 오면 양쪽을 닫는다")
    void closesOnBinaryMessage() throws Exception {
        socket.afterConnectionEstablished(session);

        socket.handleMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2})));

        assertThat(relays.closes).hasValue(1);
        assertThat(sessionClosed.get()).isEqualTo(CloseStatus.NOT_ACCEPTABLE);
        assertThat(handleCloses).hasValue(1);
    }

    @Test
    @DisplayName("Chrome 쪽에 붙지 못하면 받은 쪽을 SERVER_ERROR 로 닫고 핸들을 닫는다")
    void closesSessionWhenChromeIsUnreachable() throws Exception {
        relays.failing = true;

        socket.afterConnectionEstablished(session);

        assertThat(sessionClosed.get()).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(handleCloses).hasValue(1);
    }

    @Test
    @DisplayName("Chrome 쪽 보내기가 실패하면 양쪽을 닫는다")
    void closesBothSidesWhenRelaySendFails() throws Exception {
        socket.afterConnectionEstablished(session);
        relays.failSend = true;

        socket.handleMessage(session, new TextMessage("{}", true));

        assertThat(relays.closes).hasValue(1);
        assertThat(sessionClosed.get()).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(handleCloses).hasValue(1);
        verify(gateway, never()).touch(TARGET);
    }

    /** 연 대상과 보낸 조각, 닫힘을 기록하는 Chrome 쪽 대역이다. */
    private static final class FakeRelays implements CdpRelayConnector {

        final List<String> sent = new ArrayList<>();
        String opened;
        CdpRelayListener listener;
        final AtomicInteger closes = new AtomicInteger();
        boolean failing;
        boolean failSend;

        @Override
        public CdpRelay open(URI cdp, String kind, String id, CdpRelayListener relayListener) {
            if (failing) {
                throw new IllegalStateException("cdp relay connect failed");
            }
            opened = cdp + " " + kind + " " + id;
            listener = relayListener;
            return new CdpRelay() {
                @Override
                public void send(String fragment, boolean last) {
                    if (failSend) {
                        throw new IllegalStateException("cdp relay send failed");
                    }
                    sent.add(fragment + " " + last);
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        }
    }
}
