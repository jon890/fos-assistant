package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.domain.CdpConnection;
import com.bifos.assistant.browser.domain.CdpConnector;
import com.bifos.assistant.browser.domain.CdpEvent;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.browser.domain.CdpTargets;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 메모리에서 탭과 CDP 연결을 흉내 내는 대역이다. 보낸 명령과 붙은 탭을 검사가 본다. */
public final class FakeCdp implements CdpTargets, CdpConnector {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 탭 목록이 돌려줄 탭이다. */
    public final List<CdpTarget> pages = new CopyOnWriteArrayList<>();
    /** 보낸 명령이다. 연결이 닫힌 뒤에 보낸 것은 담지 않는다. */
    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    /** 붙은 탭 번호다. 붙은 차례대로 담는다. */
    public final List<String> attached = new CopyOnWriteArrayList<>();
    /** 앞으로 가져온 탭 번호다. */
    public final List<String> activated = new CopyOnWriteArrayList<>();

    public volatile boolean failConnect;
    public volatile boolean failList;
    /** 이 탭의 다음 {@code Page.startScreencast} 응답을 {@link #releaseScreencast()} 까지 붙잡는다. */
    public volatile String holdScreencast;

    private volatile CompletableFuture<JsonNode> heldScreencast;

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /** 보낸 명령 하나다. */
    public record Sent(String targetId, String method, Map<String, Object> params) {}

    @Override
    public List<CdpTarget> list(URI cdp) {
        if (failList) {
            throw new IllegalStateException("cdp list failed");
        }
        return List.copyOf(pages);
    }

    @Override
    public CdpTarget create(URI cdp, String url) {
        CdpTarget created = new CdpTarget("NEW" + pages.size(), "", url);
        pages.add(created);
        return created;
    }

    @Override
    public void activate(URI cdp, String id) {
        activated.add(id);
    }

    @Override
    public CdpConnection connect(URI cdp, String targetId, Consumer<CdpEvent> events, Runnable closed) {
        if (failConnect) {
            throw new IllegalStateException("cdp connect failed");
        }
        Connection connection = new Connection(targetId, events, closed);
        connections.put(targetId, connection);
        attached.add(targetId);
        return connection;
    }

    /** 그 탭이 screencast 프레임 하나를 보낸다. */
    public void frame(String targetId, int width, int height, int sessionId) {
        JsonNode params = JSON.readTree("{\"data\":\"AAAA\",\"sessionId\":" + sessionId
                + ",\"metadata\":{\"deviceWidth\":" + width + ",\"deviceHeight\":" + height + "}}");
        connections.get(targetId).events.accept(new CdpEvent("Page.screencastFrame", params));
    }

    /** 그 탭의 연결이 상대 쪽에서 끊긴다. */
    public void drop(String targetId) {
        Connection connection = connections.get(targetId);
        connection.open = false;
        connection.closed.run();
    }

    /** 그 탭의 연결을 이쪽이 닫았는가. */
    public boolean closedByUs(String targetId) {
        Connection connection = connections.get(targetId);
        return connection != null && connection.closedByUs;
    }

    /** 붙잡은 {@code Page.startScreencast} 가 있는가. */
    public boolean screencastHeld() {
        return heldScreencast != null;
    }

    /** 붙잡은 {@code Page.startScreencast} 를 연결이 닫힌 것처럼 실패로 끝낸다. */
    public void releaseScreencast() {
        heldScreencast.completeExceptionally(new IllegalStateException("cdp connection is closed"));
    }

    /** 그 메서드로 보낸 명령이다. */
    public List<Sent> sent(String method) {
        return sent.stream().filter(command -> command.method().equals(method)).toList();
    }

    /** 보낸 명령 이름을 차례대로 돌려준다. */
    public List<String> methods() {
        return sent.stream().map(Sent::method).toList();
    }

    private final class Connection implements CdpConnection {

        private final String targetId;
        private final Consumer<CdpEvent> events;
        private final Runnable closed;
        private volatile boolean open = true;
        private volatile boolean closedByUs;

        private Connection(String targetId, Consumer<CdpEvent> events, Runnable closed) {
            this.targetId = targetId;
            this.events = events;
            this.closed = closed;
        }

        @Override
        public CompletableFuture<JsonNode> send(String method, Map<String, Object> params) {
            if (!open) {
                return CompletableFuture.failedFuture(new IllegalStateException("cdp connection is closed"));
            }
            sent.add(new Sent(targetId, method, params));
            if ("Page.startScreencast".equals(method) && targetId.equals(holdScreencast)) {
                holdScreencast = null;
                heldScreencast = new CompletableFuture<>();
                return heldScreencast;
            }
            if ("Page.getNavigationHistory".equals(method)) {
                return CompletableFuture.completedFuture(
                        JSON.readTree("{\"currentIndex\":1,\"entries\":[{\"id\":11},{\"id\":12}]}"));
            }
            return CompletableFuture.completedFuture(JSON.createObjectNode());
        }

        @Override
        public void close() {
            open = false;
            closedByUs = true;
        }
    }
}
