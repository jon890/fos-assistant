package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.BrowserGateway;
import com.bifos.assistant.browser.application.model.BrowserUsageHandle;
import com.bifos.assistant.browser.application.model.GatewayTarget;
import com.bifos.assistant.browser.domain.CdpRelay;
import com.bifos.assistant.browser.domain.CdpRelayConnector;
import com.bifos.assistant.browser.domain.CdpRelayListener;
import jakarta.websocket.Session;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 브라우저 중계의 WebSocket 이다. 받은 연결 하나에 Chrome 쪽 연결 하나를 열고 양쪽 글 메시지를 조각째 그대로 잇는다.
 *
 * <p>계약은 {@code backend/docs/flow.md} 의 「WebSocket」 이다. 조각을 모으지 않으므로 사진 바이트가 든 큰 CDP 메시지도 세션마다
 * 큰 버퍼를 잡지 않는다. 한쪽으로 가는 조각은 앞 조각을 보낸 뒤에 보낸다. 한 메시지(조각의 합)가 상한을 넘거나 바이너리 메시지가 오면 양쪽을
 * 닫는다. 한쪽이 닫히면 다른 쪽도 닫는다.
 *
 * <p>연결이 열려 있는 동안 {@link BrowserGateway#hold} 의 핸들을 쥐어 자동 중지를 막는다. 아무것도 오가지 않은 채
 * {@link BrowserGateway#idleTimeout} 이 지나면 컨테이너가 세션을 닫고 핸들도 놓는다. 표식과 경로는 로그에 싣지 않고, 닫는 까닭은
 * 종류만 남긴다.
 */
@Slf4j
@Component
public class BrowserGatewaySocket extends AbstractWebSocketHandler {

    /** 받은 메시지 하나(조각의 합)의 상한이다. 끝없는 메시지를 막는다. */
    static final long MAX_MESSAGE_CHARS = 64L * 1024 * 1024;

    /** 연결 하나의 {@link Link} 를 두는 세션 속성이다. */
    private static final String LINK = BrowserGatewaySocket.class.getName() + ".link";

    private final BrowserGateway gateway;
    private final CdpRelayConnector connector;
    private final long maxMessageChars;

    @Autowired
    public BrowserGatewaySocket(BrowserGateway gateway, CdpRelayConnector connector) {
        this(gateway, connector, MAX_MESSAGE_CHARS);
    }

    BrowserGatewaySocket(BrowserGateway gateway, CdpRelayConnector connector, long maxMessageChars) {
        this.gateway = gateway;
        this.connector = connector;
        this.maxMessageChars = maxMessageChars;
    }

    /** 조각을 받는 대로 넘긴다. Tomcat 은 버퍼가 찰 때마다 조각을 넘긴다. */
    @Override
    public boolean supportsPartialMessages() {
        return true;
    }

    /**
     * 핸들을 쥐고 Chrome 쪽에 붙는다. 실패는 여기서 끝낸다. 던지면 Spring 이 표식이 든 세션 주소를 ERROR 로그에 남긴다.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Link link = null;
        try {
            GatewayTarget target = (GatewayTarget) session.getAttributes().get(BrowserGatewayHandshake.TARGET);
            String kind = (String) session.getAttributes().get(BrowserGatewayHandshake.KIND);
            String id = (String) session.getAttributes().get(BrowserGatewayHandshake.TARGET_ID);
            if (target == null || kind == null || id == null) {
                // handshake 판정을 지나지 않은 세션이다
                closeQuietly(session, CloseStatus.SERVER_ERROR);
                return;
            }
            link = new Link(session, target, gateway.hold(target));
            session.getAttributes().put(LINK, link);
            limitIdle(session);
            CdpRelay relay;
            try {
                relay = connector.open(target.cdp(), kind, id, link);
            } catch (RuntimeException ex) {
                log.warn(
                        "browser gateway relay connect failed error={}",
                        ex.getClass().getSimpleName());
                link.close(CloseStatus.SERVER_ERROR, "connect_failed");
                return;
            }
            link.attach(relay);
        } catch (RuntimeException ex) {
            log.warn(
                    "browser gateway socket setup failed error={}",
                    ex.getClass().getSimpleName());
            if (link != null) {
                link.close(CloseStatus.SERVER_ERROR, "setup_failed");
            } else {
                closeQuietly(session, CloseStatus.SERVER_ERROR);
            }
        }
    }

    /**
     * 아무것도 주고받지 않는 세션을 자동 중지와 같은 유휴 시간이 지나면 닫게 한다. 반쯤 끊긴 연결이 핸들을 계속 쥐지 않게 한다. 컨테이너의
     * 세션을 꺼내지 못하면 건너뛴다.
     */
    private void limitIdle(WebSocketSession session) {
        if (!(session instanceof NativeWebSocketSession wrapper)) {
            return;
        }
        Session container = wrapper.getNativeSession(Session.class);
        if (container != null) {
            container.setMaxIdleTimeout(gateway.idleTimeout().toMillis());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Link link = linkOf(session);
        if (link != null) {
            link.forward(message.getPayload(), message.isLast());
        }
    }

    /** 중계는 글 메시지만 잇는다. */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Link link = linkOf(session);
        if (link != null) {
            link.close(CloseStatus.NOT_ACCEPTABLE, "binary");
        } else {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Link link = linkOf(session);
        if (link != null) {
            link.close(CloseStatus.SERVER_ERROR, "transport_error");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Link link = linkOf(session);
        if (link != null) {
            link.close(null, "client_closed");
        }
    }

    private static Link linkOf(WebSocketSession session) {
        return (Link) session.getAttributes().get(LINK);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException | RuntimeException ex) {
            log.debug(
                    "browser gateway session close failed error={}",
                    ex.getClass().getSimpleName());
        }
    }

    /** 받은 연결 하나와 Chrome 쪽 연결 하나를 잇는다. */
    @RequiredArgsConstructor
    private final class Link implements CdpRelayListener {

        private final WebSocketSession session;
        private final GatewayTarget target;
        private final BrowserUsageHandle handle;
        /** 세션 쪽 보내기를 한 번에 하나로 둔다. 앞 조각을 다 보내야 다음 조각을 보낸다. */
        private final ReentrantLock sending = new ReentrantLock();

        private final AtomicBoolean closed = new AtomicBoolean();

        private volatile CdpRelay relay;
        /** 지금 받고 있는 메시지의 지난 조각 길이의 합이다. Tomcat 이 한 세션의 메시지를 차례로 넘기므로 한 스레드만 쓴다. */
        private long pending;

        /** 붙은 Chrome 쪽 연결을 둔다. 그 사이 닫혔으면 그 연결도 닫는다. */
        void attach(CdpRelay opened) {
            relay = opened;
            if (closed.get()) {
                opened.close();
            }
        }

        /** 받은 조각을 Chrome 에 보낸다. 메시지가 끝나면 활동을 기록한다. */
        void forward(String fragment, boolean last) {
            CdpRelay current = relay;
            if (current == null || closed.get()) {
                return;
            }
            long total = pending + fragment.length();
            if (total > maxMessageChars) {
                close(CloseStatus.TOO_BIG_TO_PROCESS, "too_large");
                return;
            }
            pending = last ? 0 : total;
            try {
                current.send(fragment, last);
            } catch (RuntimeException ex) {
                log.warn(
                        "browser gateway relay send failed error={}",
                        ex.getClass().getSimpleName());
                close(CloseStatus.SERVER_ERROR, "relay_send_failed");
                return;
            }
            if (last) {
                touch();
            }
        }

        /** Chrome 이 보낸 조각을 받은 쪽에 보낸다. 보내기가 끝나야 Chrome 쪽에서 다음 조각을 읽는다. */
        @Override
        public void onFragment(String fragment, boolean last) {
            sending.lock();
            try {
                if (closed.get()) {
                    return;
                }
                session.sendMessage(new TextMessage(fragment, last));
            } catch (IOException | RuntimeException ex) {
                log.warn(
                        "browser gateway session send failed error={}",
                        ex.getClass().getSimpleName());
                close(CloseStatus.SERVER_ERROR, "session_send_failed");
            } finally {
                sending.unlock();
            }
        }

        @Override
        public void onClosed() {
            close(CloseStatus.GOING_AWAY, "chrome_closed");
        }

        /**
         * 양쪽과 핸들을 닫는다. 두 번 불러도 한 번만 닫는다.
         *
         * @param status 받은 쪽을 닫을 상태. 받은 쪽이 이미 닫혔으면 비어 있다
         * @param reason 로그에 남길 까닭의 종류
         */
        void close(CloseStatus status, String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            log.info("browser gateway socket closed reason={}", reason);
            CdpRelay current = relay;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException ex) {
                    log.debug(
                            "browser gateway relay close failed error={}",
                            ex.getClass().getSimpleName());
                }
            }
            handle.close();
            if (status != null) {
                closeQuietly(session, status);
            }
        }

        private void touch() {
            try {
                gateway.touch(target);
            } catch (RuntimeException ex) {
                // 활동 기록이 실패해도 중계는 잇는다. 자동 중지는 핸들이 막는다
                log.warn("browser gateway touch failed error={}", ex.getClass().getSimpleName());
            }
        }
    }
}
