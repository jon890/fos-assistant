package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.CdpRelay;
import com.bifos.assistant.browser.domain.CdpRelayConnector;
import com.bifos.assistant.browser.domain.CdpRelayListener;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JDK 의 {@link WebSocket} 으로 Chrome 의 {@code /devtools/<종류>/<번호>} 에 붙는 브라우저 중계의 Chrome 쪽이다.
 *
 * <p>주소는 {@code cdp} 의 host 와 port 로 만들고 {@code Origin} 은 보내지 않는다. 글 메시지 조각은 모으지 않고 받은 그대로 넘긴다.
 * 조각 하나를 넘긴 뒤에 다음 조각을 읽으므로, 받는 쪽이 느리면 Chrome 쪽 읽기도 기다린다. 사진 바이트가 든 큰 CDP 메시지도 연결마다 큰
 * 버퍼를 잡지 않는다. 대상 번호와 주소는 로그에 싣지 않는다.
 */
@Slf4j
@Component
public class WebSocketCdpRelayConnector implements CdpRelayConnector, AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 조각 하나의 보내기가 이만큼 끝나지 않으면 실패다. 멈춘 Chrome 이 받는 쪽 스레드를 붙잡지 않게 한다. */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    private static final Set<String> KINDS = Set.of("page", "browser");
    private static final Pattern TARGET_ID = Pattern.compile("^[A-Za-z0-9-]{1,128}$");

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    @Override
    public CdpRelay open(URI cdp, String kind, String id, CdpRelayListener listener) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("relay kind is not valid");
        }
        if (id == null || !TARGET_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("relay target id is not valid");
        }
        if (cdp == null || !"http".equals(cdp.getScheme()) || cdp.getHost() == null) {
            throw new IllegalArgumentException("cdp address is not valid");
        }
        URI address = URI.create("ws://" + cdp.getRawAuthority() + "/devtools/" + kind + "/" + id);
        Relay relay = new Relay(listener);
        CompletableFuture<WebSocket> opening =
                client.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(address, relay);
        try {
            relay.attach(opening.get(CONNECT_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS));
            return relay;
        } catch (InterruptedException ex) {
            abandon(opening, relay);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cdp relay connect interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            abandon(opening, relay);
            throw new IllegalStateException("cdp relay connect failed", ex);
        }
    }

    /** 붙기를 포기한다. 닫힘 알림을 부르지 않게 끝내고, 늦게 열린 소켓은 닫는다. */
    private static void abandon(CompletableFuture<WebSocket> opening, Relay relay) {
        relay.end(false);
        opening.whenComplete((webSocket, error) -> {
            if (webSocket != null) {
                webSocket.abort();
            }
        });
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    /** 연결 하나다. WebSocket 의 듣는 쪽과 보내는 쪽을 함께 맡는다. */
    @RequiredArgsConstructor
    private static final class Relay implements WebSocket.Listener, CdpRelay {

        private final CdpRelayListener listener;
        private final AtomicBoolean ended = new AtomicBoolean();

        private volatile WebSocket socket;

        void attach(WebSocket webSocket) {
            this.socket = webSocket;
            if (ended.get()) {
                webSocket.abort();
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
            if (ended.get()) {
                webSocket.abort();
                return;
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (ended.get()) {
                return null;
            }
            try {
                // data 는 돌아온 뒤 다시 쓰일 수 있어 문자열로 떠서 넘긴다
                listener.onFragment(data.toString(), last);
            } catch (RuntimeException ex) {
                log.warn("cdp relay listener failed error={}", ex.getClass().getSimpleName());
                if (end(true)) {
                    webSocket.abort();
                }
                return null;
            }
            webSocket.request(1);
            return null;
        }

        /** 중계는 글 메시지만 잇는다. 바이너리가 오면 닫는다. */
        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            log.warn("cdp relay closing reason=binary");
            if (end(true)) {
                webSocket.abort();
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            end(true);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            end(true);
        }

        /** 한 번에 하나만 보낸다. 앞 조각의 보내기가 끝나야 이 잠금을 놓는다. */
        @Override
        public synchronized void send(String fragment, boolean last) {
            if (ended.get()) {
                throw new IllegalStateException("cdp relay is closed");
            }
            try {
                socket.sendText(fragment, last).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cdp relay send interrupted", ex);
            } catch (ExecutionException | TimeoutException ex) {
                throw new IllegalStateException("cdp relay send failed", ex);
            }
        }

        @Override
        public void close() {
            if (end(false) && socket != null) {
                socket.abort();
            }
        }

        /** 한 번만 끝낸다. 상대가 닫았거나 실패했으면 닫힘을 알린다. 처음 끝냈으면 참이다. */
        boolean end(boolean notify) {
            if (!ended.compareAndSet(false, true)) {
                return false;
            }
            if (notify) {
                try {
                    listener.onClosed();
                } catch (RuntimeException ex) {
                    log.warn(
                            "cdp relay close handler failed error={}",
                            ex.getClass().getSimpleName());
                }
            }
            return true;
        }
    }
}
