package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.CdpConnection;
import com.bifos.assistant.browser.domain.CdpConnector;
import com.bifos.assistant.browser.domain.CdpEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
 *
 * <p>사건 처리기와 닫힘 알림은 WebSocket 을 읽는 스레드가 아니라 연결마다 하나인 데몬 스레드에서 차례대로 부른다. 명령 응답도 읽는 스레드
 * 밖에서 완료한다. 처리기가 오래 걸려도 읽기와 명령 응답이 막히지 않는다.
 */
@Slf4j
@Component
public class WebSocketCdpConnector implements CdpConnector, AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    /** 메시지 하나의 상한이다. 넘으면 연결을 끊는다. */
    private static final int MAX_MESSAGE_CHARS = 4 * 1024 * 1024;
    /** 큰 메시지를 받은 뒤 버퍼가 이보다 크면 새로 만들어 메모리를 돌려준다. */
    private static final int BUFFER_KEEP_CHARS = 1024 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client;
    private final Duration commandTimeout;
    private final int maxMessageChars;
    private final AtomicLong connections = new AtomicLong();

    @Autowired
    public WebSocketCdpConnector() {
        this(COMMAND_TIMEOUT, MAX_MESSAGE_CHARS);
    }

    WebSocketCdpConnector(Duration commandTimeout, int maxMessageChars) {
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.commandTimeout = commandTimeout;
        this.maxMessageChars = maxMessageChars;
    }

    @Override
    public CdpConnection connect(URI cdp, String targetId, Consumer<CdpEvent> events, Runnable closed) {
        HttpCdpTargets.requireTargetId(targetId);
        if (cdp == null || !"http".equals(cdp.getScheme()) || cdp.getHost() == null) {
            throw new IllegalArgumentException("cdp address is not valid");
        }
        URI address = URI.create("ws://" + cdp.getRawAuthority() + "/devtools/page/" + targetId);
        Session session = new Session(events, closed);
        CompletableFuture<WebSocket> opening =
                client.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(address, session);
        try {
            WebSocket socket = opening.get(CONNECT_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
            session.attach(socket);
            return session;
        } catch (InterruptedException ex) {
            abandon(opening, session);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cdp connect interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            abandon(opening, session);
            throw new IllegalStateException("cdp connect failed", ex);
        }
    }

    /** 붙기를 포기한다. 사건과 닫힘 알림을 부르지 않게 끝내고, 늦게 열린 소켓은 닫는다. */
    private static void abandon(CompletableFuture<WebSocket> opening, Session session) {
        session.end(false);
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

    /** 연결 하나의 상태다. WebSocket 의 듣는 쪽과 명령을 보내는 쪽을 함께 맡는다. */
    private final class Session implements WebSocket.Listener, CdpConnection {

        private final Consumer<CdpEvent> events;
        private final Runnable closed;
        private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        private final AtomicBoolean ended = new AtomicBoolean();
        /** 사건 처리기와 닫힘 알림을 차례대로 부르는 스레드다. */
        private final ExecutorService handlers;

        private StringBuilder buffer = new StringBuilder();
        /** {@link #close()} 나 붙기 실패로 끝났다. 줄에 남은 사건도 부르지 않는다. */
        private volatile boolean silenced;

        private volatile WebSocket socket;
        /** JDK WebSocket 은 앞의 보내기가 끝나야 다음을 받는다. 보내기를 차례로 잇는다. */
        private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

        Session(Consumer<CdpEvent> events, Runnable closed) {
            this.events = events;
            this.closed = closed;
            String name = "cdp-events-" + connections.incrementAndGet();
            this.handlers = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, name);
                thread.setDaemon(true);
                return thread;
            });
        }

        void attach(WebSocket webSocket) {
            this.socket = webSocket;
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
            if (buffer.length() + data.length() > maxMessageChars) {
                log.warn("cdp message too large, closing connection");
                buffer = new StringBuilder();
                end(true);
                webSocket.abort();
                return null;
            }
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                if (buffer.capacity() > BUFFER_KEEP_CHARS) {
                    buffer = new StringBuilder();
                } else {
                    buffer.setLength(0);
                }
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
            String text;
            try {
                ObjectNode message = JSON.createObjectNode();
                message.put("id", id);
                message.put("method", method);
                message.set("params", JSON.valueToTree(params == null ? Map.of() : params));
                text = JSON.writeValueAsString(message);
            } catch (RuntimeException ex) {
                // 원인 예외의 메시지에 인자 값이 실릴 수 있어 싣지 않는다
                result.completeExceptionally(new IllegalArgumentException("cdp params not serializable " + method));
                return result;
            }
            pending.put(id, result);
            result.orTimeout(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((value, error) -> pending.remove(id));
            synchronized (this) {
                sending = sending.handle((value, error) -> null)
                        // 시간 초과 등으로 이미 끝난 명령은 보내지 않는다
                        .thenCompose(ignored ->
                                result.isDone() ? CompletableFuture.completedFuture(null) : socket.sendText(text, true))
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
                // 완료 뒤의 사슬이 읽는 스레드에서 돌지 않게 다른 스레드에서 완료한다
                if (node.has("error")) {
                    IllegalStateException error = new IllegalStateException("cdp command failed code="
                            + node.path("error").path("code").asInt());
                    CompletableFuture.runAsync(() -> waiting.completeExceptionally(error));
                } else {
                    waiting.completeAsync(() -> node.path("result"));
                }
                return;
            }
            String method = node.path("method").asString("");
            if (method.isEmpty()) {
                return;
            }
            CdpEvent event = new CdpEvent(method, node.has("params") ? node.get("params") : JSON.createObjectNode());
            handle(() -> events.accept(event), "event " + method);
        }

        /** 처리기 스레드에 넘긴다. 이미 닫았거나 그 스레드가 끝났으면 버린다. */
        private void handle(Runnable handler, String what) {
            try {
                handlers.execute(() -> {
                    if (silenced) {
                        return;
                    }
                    try {
                        handler.run();
                    } catch (RuntimeException ex) {
                        log.warn(
                                "cdp {} handler failed error={}",
                                what,
                                ex.getClass().getSimpleName());
                    }
                });
            } catch (RejectedExecutionException ex) {
                log.debug("cdp handler dropped after end");
            }
        }

        /** 한 번만 끝낸다. 기다리던 명령을 실패로 끝내고, 상대가 끊었으면 닫힘을 알린다. 처음 끝냈으면 참이다. */
        private boolean end(boolean notify) {
            if (!ended.compareAndSet(false, true)) {
                return false;
            }
            if (notify) {
                // 줄에 남은 사건 뒤에 한 번 알린다
                handle(closed, "close");
            } else {
                silenced = true;
            }
            handlers.shutdown();
            IllegalStateException closedError = new IllegalStateException("cdp connection is closed");
            List<CompletableFuture<JsonNode>> waiting = List.copyOf(pending.values());
            pending.clear();
            if (!waiting.isEmpty()) {
                CompletableFuture.runAsync(() -> waiting.forEach(each -> each.completeExceptionally(closedError)));
            }
            return true;
        }
    }
}
