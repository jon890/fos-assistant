package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.CdpConnection;
import com.bifos.assistant.browser.domain.CdpConnector;
import com.bifos.assistant.browser.domain.CdpEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * JDK 의 {@link WebSocket} 으로 탭 하나의 CDP 에 붙는다.
 *
 * <p>WebSocket 주소는 Chrome 이 준 {@code webSocketDebuggerUrl} 을 쓰지 않고 {@code cdp} 의 host 와 port 로 만든다. Chrome 이
 * 알려 주는 host 는 컨테이너 안의 이름일 수 있다. {@code Origin} 은 보내지 않는다.
 *
 * <p>명령은 번호로 응답과 짝짓는다. 정한 시간 안에 답이 없으면 실패로 끝낸다. 연결이 끊기면 기다리던 명령을 모두 실패로 끝내고 닫힘을
 * 알린다. 메시지 조각은 모아서 한 JSON 으로 읽는다. screencast 프레임 하나가 수백 KB 다.
 */
@Slf4j
@Component
public class WebSocketCdpConnector implements CdpConnector, AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    /** 메시지 하나의 상한이다. 넘으면 연결을 끊는다. */
    private static final int MAX_MESSAGE_CHARS = 16 * 1024 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client;
    private final Duration commandTimeout;

    @Autowired
    public WebSocketCdpConnector() {
        this(COMMAND_TIMEOUT);
    }

    WebSocketCdpConnector(Duration commandTimeout) {
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.commandTimeout = commandTimeout;
    }

    @Override
    public CdpConnection connect(URI cdp, String targetId, Consumer<CdpEvent> events, Runnable closed) {
        HttpCdpTargets.requireTargetId(targetId);
        if (cdp == null || !"http".equals(cdp.getScheme()) || cdp.getHost() == null) {
            throw new IllegalArgumentException("cdp address is not valid");
        }
        URI address = URI.create("ws://" + cdp.getRawAuthority() + "/devtools/page/" + targetId);
        Session session = new Session(events, closed);
        try {
            WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(address, session)
                    .get(CONNECT_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
            session.attach(socket);
            return session;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cdp connect interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("cdp connect failed", ex);
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    /** 연결 하나의 상태다. WebSocket 의 듣는 쪽과 명령을 보내는 쪽을 함께 맡는다. */
    private final class Session implements WebSocket.Listener, CdpConnection {

        private final Consumer<CdpEvent> events;
        private final Runnable closed;
        private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final StringBuilder buffer = new StringBuilder();
        private volatile WebSocket socket;
        /** JDK WebSocket 은 앞의 보내기가 끝나야 다음을 받는다. 보내기를 차례로 잇는다. */
        private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

        Session(Consumer<CdpEvent> events, Runnable closed) {
            this.events = events;
            this.closed = closed;
        }

        void attach(WebSocket webSocket) {
            this.socket = webSocket;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (buffer.length() + data.length() > MAX_MESSAGE_CHARS) {
                log.warn("cdp message too large, closing connection");
                end(true);
                webSocket.abort();
                return null;
            }
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                dispatch(message);
            }
            webSocket.request(1);
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

        @Override
        public CompletableFuture<JsonNode> send(String method, Map<String, Object> params) {
            CompletableFuture<JsonNode> result = new CompletableFuture<>();
            if (ended.get()) {
                result.completeExceptionally(new IllegalStateException("cdp connection is closed"));
                return result;
            }
            long id = ids.incrementAndGet();
            ObjectNode message = JSON.createObjectNode();
            message.put("id", id);
            message.put("method", method);
            message.set("params", JSON.valueToTree(params == null ? Map.of() : params));
            String text = JSON.writeValueAsString(message);
            pending.put(id, result);
            result.orTimeout(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((value, error) -> pending.remove(id));
            synchronized (this) {
                sending = sending.handle((value, error) -> null)
                        .thenCompose(ignored -> socket.sendText(text, true))
                        .whenComplete((value, error) -> {
                            if (error != null) {
                                result.completeExceptionally(new IllegalStateException("cdp send failed " + method));
                            }
                        });
            }
            // 연결이 그 사이 끊겼으면 end 가 이 명령을 보지 못했을 수 있다
            if (ended.get()) {
                result.completeExceptionally(new IllegalStateException("cdp connection is closed"));
            }
            return result;
        }

        @Override
        public void close() {
            if (end(false)) {
                socket.abort();
            }
        }

        private void dispatch(String message) {
            JsonNode node;
            try {
                node = JSON.readTree(message);
            } catch (RuntimeException ex) {
                log.debug("cdp message is not json");
                return;
            }
            if (node.has("id")) {
                CompletableFuture<JsonNode> waiting =
                        pending.remove(node.path("id").asLong());
                if (waiting == null) {
                    return;
                }
                if (node.has("error")) {
                    waiting.completeExceptionally(new IllegalStateException("cdp command failed code="
                            + node.path("error").path("code").asInt()));
                } else {
                    waiting.complete(node.path("result"));
                }
                return;
            }
            String method = node.path("method").asString("");
            if (method.isEmpty()) {
                return;
            }
            JsonNode params = node.has("params") ? node.get("params") : JSON.createObjectNode();
            try {
                events.accept(new CdpEvent(method, params));
            } catch (RuntimeException ex) {
                log.warn(
                        "cdp event handler failed event={} error={}",
                        method,
                        ex.getClass().getSimpleName());
            }
        }

        /** 한 번만 끝낸다. 기다리던 명령을 실패로 끝내고, 상대가 끊었으면 닫힘을 알린다. 처음 끝냈으면 참이다. */
        private boolean end(boolean notify) {
            if (!ended.compareAndSet(false, true)) {
                return false;
            }
            IllegalStateException closedError = new IllegalStateException("cdp connection is closed");
            pending.values().forEach(waiting -> waiting.completeExceptionally(closedError));
            pending.clear();
            if (notify) {
                try {
                    closed.run();
                } catch (RuntimeException ex) {
                    log.warn("cdp close handler failed error={}", ex.getClass().getSimpleName());
                }
            }
            return true;
        }
    }
}
