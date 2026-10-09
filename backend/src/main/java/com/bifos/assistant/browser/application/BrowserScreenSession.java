package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.model.BrowserScreenInput;
import com.bifos.assistant.browser.application.model.BrowserScreenSink;
import com.bifos.assistant.browser.application.model.BrowserUsageHandle;
import com.bifos.assistant.browser.domain.CdpConnection;
import com.bifos.assistant.browser.domain.CdpConnector;
import com.bifos.assistant.browser.domain.CdpEvent;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.browser.domain.CdpTargets;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/** 로그인 화면 하나다. CDP 연결, 받는 쪽, 사용 핸들을 하나씩 쥔다. 계약은 {@code docs/backend/user-browser.md} 의 「로그인 화면」 이다. */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Accessors(fluent = true)
public class BrowserScreenSession {

    /** screencast 인자다. 프레임 하나가 수백 KB 다. */
    static final Map<String, Object> SCREENCAST =
            Map.of("format", "jpeg", "quality", 60, "maxWidth", 1600, "maxHeight", 2000);

    static final String REPLACED = "replaced";
    static final String STOPPED = "stopped";
    static final String TIMEOUT = "timeout";
    /** 프레임 없이 연이어 끊긴 연결을 다시 잇는 상한이다. */
    static final int MAX_RECONNECTS = 3;

    @Getter(AccessLevel.PACKAGE)
    private final Long browserId;

    @Getter(AccessLevel.PACKAGE)
    private final Long userId;

    private final URI cdp;
    private final CdpTargets targets;
    private final CdpConnector connector;
    private final BrowserUsageHandle usage;
    private final BrowserScreenSink sink;
    private final ScheduledExecutorService scheduler;
    private final Consumer<BrowserScreenSession> ended;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private final Object lock = new Object();

    // 아래 상태는 lock 으로 지킨다. 탭을 옮길 때마다 세대가 오른다
    private CdpConnection connection;
    private String targetId;
    private long generation;
    private boolean mouseDown;
    private List<CdpTarget> tabs = List.of();
    // 프레임 없이 연이어 다시 이은 횟수, 마지막 resize 인자, 마지막 프레임의 화면 크기(CSS 픽셀)다
    private int reconnects;
    private volatile Map<String, Object> metrics;
    private volatile Viewport viewport;
    private ScheduledFuture<?> resizeTask;

    boolean closed() {
        return closed.get();
    }

    /** 지금 탭(없으면 새 빈 탭)에 붙어 screencast 를 시작한다. {@code url} 이 없으면 지금 주소에 머문다. 붙지 못하면 런타임 예외다. */
    void start(String url, Duration timeout, Duration tabInterval, Duration heartbeat) {
        List<CdpTarget> pages = targets.list(cdp);
        if (pages.isEmpty()) {
            pages = List.of(targets.create(cdp, "about:blank"));
        }
        synchronized (lock) {
            tabs = pages;
        }
        attach(pages.get(0).id(), url);
        sink.onClose(() -> close(null));
        sendTabs();
        long interval = tabInterval.toMillis();
        schedule(scheduler.schedule(() -> close(TIMEOUT), timeout.toMillis(), TimeUnit.MILLISECONDS));
        schedule(scheduler.scheduleWithFixedDelay(this::pollTabs, interval, interval, TimeUnit.MILLISECONDS));
        schedule(scheduler.scheduleWithFixedDelay(
                this::heartbeat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS));
    }

    /** 입력 하나를 CDP 명령으로 보내고 결과는 기다리지 않는다. 화면이 이미 닫혔으면 {@code BROWSER_SCREEN_CLOSED} 다. */
    void input(BrowserScreenInput input) {
        if (closed.get()) {
            throw new ApiException(ErrorCode.BROWSER_SCREEN_CLOSED, "browser screen is closed");
        }
        CdpConnection current;
        synchronized (lock) {
            current = connection;
        }
        if (input.kind() != BrowserScreenInput.Kind.TAB && current == null) {
            return;
        }
        switch (input.kind()) {
            case TAB -> guard("tab switch", () -> selectTab(input.targetId()));
            case MOUSE -> mouse(current, input);
            case WHEEL -> wheel(current, input);
            // 사용자가 스크립트를 정하지 못한다. 페이지 처음과 끝에 쓰는 고정 식만 실행한다.
            case SCROLL ->
                command(
                        current,
                        "Runtime.evaluate",
                        Map.of(
                                "expression",
                                "window.scrollTo(0, "
                                        + ("top".equals(input.action()) ? "0" : "document.documentElement.scrollHeight")
                                        + ")"));
            case KEY -> key(current, input.key());
            case TEXT -> command(current, "Input.insertText", Map.of("text", input.text()));
            case NAVIGATE -> command(current, "Page.navigate", Map.of("url", input.url()));
            case BACK -> back(current);
            case RELOAD -> command(current, "Page.reload", Map.of());
            case RESIZE -> resize(input);
        }
    }

    /** 한 번만 닫는다. 예약한 일, 연결, 받는 쪽, 사용 핸들을 닫고 등록부에서 뺀다. 받는 쪽이 먼저 끊겼으면 {@code reason} 을 비운다. */
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CdpConnection current;
        synchronized (lock) {
            tasks.forEach(task -> task.cancel(false));
            tasks.clear();
            if (resizeTask != null) {
                resizeTask.cancel(false);
            }
            current = connection;
            connection = null;
            generation++;
        }
        if (reason != null) {
            sink.trySend("closed", Map.of("reason", reason));
        }
        sink.complete();
        if (current != null) {
            current.close();
        }
        usage.close();
        ended.accept(this);
    }

    /** 150ms 안에 겹친 크기 변경은 마지막 것만 현재 탭에 적용한다. */
    private void resize(BrowserScreenInput input) {
        synchronized (lock) {
            if (closed.get()) {
                return;
            }
            metrics = Map.of("width", input.width(), "height", input.height(), "deviceScaleFactor", 1, "mobile", false);
            if (resizeTask != null) {
                resizeTask.cancel(false);
            }
            resizeTask = scheduler.schedule(
                    () -> {
                        synchronized (lock) {
                            if (!closed.get() && connection != null) {
                                command(connection, "Emulation.setDeviceMetricsOverride", metrics);
                            }
                            resizeTask = null;
                        }
                    },
                    150,
                    TimeUnit.MILLISECONDS);
        }
    }

    /** 그 탭에 붙는다. 앞의 연결은 닫는다. 세대가 바뀐 뒤의 사건과 명령 실패는 버린다. */
    private void attach(String id, String url) {
        long attached;
        CdpConnection previous;
        Map<String, Object> resized = metrics;
        synchronized (lock) {
            if (resizeTask != null) {
                resizeTask.cancel(false);
            }
            resizeTask = null;
            attached = ++generation;
            previous = connection;
            connection = null;
            targetId = id;
            mouseDown = false;
            viewport = null;
        }
        if (previous != null) {
            previous.close();
        }
        CdpConnection opened =
                connector.connect(cdp, id, event -> onEvent(attached, event), () -> onConnectionLost(attached));
        synchronized (lock) {
            if (closed.get() || attached != generation) {
                opened.close();
                return;
            }
            connection = opened;
        }
        try {
            opened.send("Page.enable", Map.of());
            if (resized != null) {
                command(opened, "Emulation.setDeviceMetricsOverride", resized);
            }
            if (url != null) {
                opened.send("Page.navigate", Map.of("url", url));
            }
            opened.send("Page.startScreencast", SCREENCAST).join();
        } catch (RuntimeException ex) {
            synchronized (lock) {
                if (attached == generation) {
                    throw ex;
                }
            }
        }
    }

    private void onEvent(long attached, CdpEvent event) {
        if (!"Page.screencastFrame".equals(event.method())) {
            return;
        }
        CdpConnection current;
        synchronized (lock) {
            if (attached != generation) {
                return;
            }
            current = connection;
            reconnects = 0;
        }
        JsonNode params = event.params();
        JsonNode metadata = params.path("metadata");
        int width = (int) Math.round(metadata.path("deviceWidth").asDouble());
        int height = (int) Math.round(metadata.path("deviceHeight").asDouble());
        if (width > 0 && height > 0) {
            viewport = new Viewport(width, height);
        }
        if (!sink.send("frame", Map.of("data", params.path("data").asString(""), "width", width, "height", height))) {
            close(null);
            return;
        }
        if (current != null) {
            command(
                    current,
                    "Page.screencastFrameAck",
                    Map.of("sessionId", params.path("sessionId").asInt()));
        }
    }

    /** 붙은 탭의 연결이 끊겼다. 남은 탭으로 옮기고, 탭이 없거나 재연결 상한을 넘으면 화면을 닫는다. */
    private void onConnectionLost(long attached) {
        boolean exhausted;
        synchronized (lock) {
            if (attached != generation || closed.get()) {
                return;
            }
            exhausted = ++reconnects > MAX_RECONNECTS;
        }
        if (exhausted) {
            log.debug("browser screen reconnect limit reached id={}", browserId);
            close(STOPPED);
            return;
        }
        try {
            scheduler.execute(() -> guard("tab recovery", () -> {
                List<CdpTarget> pages = listTabs();
                if (pages != null) {
                    synchronized (lock) {
                        tabs = pages;
                    }
                    attach(pages.get(0).id(), null);
                    sendTabs();
                }
            }));
        } catch (RejectedExecutionException ex) {
            close(STOPPED);
        }
    }

    /** 탭 목록을 본다. 바뀌면 알리고, 새 탭이 생겼으면 그리로 옮기고, 붙은 탭이 사라졌으면 남은 탭으로 옮긴다. */
    private void pollTabs() {
        guard("tab check", () -> {
            List<CdpTarget> pages = listTabs();
            if (pages == null) {
                return;
            }
            List<CdpTarget> before;
            String current;
            synchronized (lock) {
                before = tabs;
                current = targetId;
                tabs = pages;
            }
            Set<String> known = before.stream().map(CdpTarget::id).collect(Collectors.toSet());
            String next = pages.stream()
                    .map(CdpTarget::id)
                    .filter(id -> !known.contains(id))
                    .findFirst()
                    .orElse(null);
            if (next == null && pages.stream().noneMatch(page -> page.id().equals(current))) {
                next = pages.get(0).id();
            }
            if (next != null) {
                moveTo(next);
            }
            if (next != null || !pages.equals(before)) {
                sendTabs();
            }
        });
    }

    /** 받는 쪽이 끊겼으면 {@code closed} 없이 닫는다. */
    private void heartbeat() {
        if (!closed.get() && !sink.ping()) {
            close(null);
        }
    }

    /** 탭 목록을 읽는다. 탭이 하나도 없으면 {@code stopped} 로 닫고 {@code null} 이다. */
    private List<CdpTarget> listTabs() {
        List<CdpTarget> pages = targets.list(cdp);
        if (pages.isEmpty()) {
            close(STOPPED);
            return null;
        }
        return pages;
    }

    /** 고른 탭으로 옮긴다. 목록에 없는 탭은 무시한다. */
    private void selectTab(String id) {
        synchronized (lock) {
            if (id.equals(targetId) || tabs.stream().noneMatch(tab -> tab.id().equals(id))) {
                return;
            }
        }
        moveTo(id);
        sendTabs();
    }

    private void moveTo(String id) {
        try {
            targets.activate(cdp, id);
        } catch (RuntimeException ex) {
            // 앞으로 가져오지 못해도 screencast 는 그 탭에서 돈다
            log.debug("browser screen tab activate failed id={}", browserId);
        }
        attach(id, null);
    }

    /** 탭 목록을 알린다. 받는 쪽이 끊겼으면 화면을 닫는다. */
    private void sendTabs() {
        List<Map<String, Object>> views = new ArrayList<>();
        synchronized (lock) {
            for (CdpTarget tab : tabs) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", tab.id());
                view.put("title", tab.title());
                view.put("url", tab.url());
                view.put("active", tab.id().equals(targetId));
                views.add(view);
            }
        }
        if (!sink.send("tabs", views)) {
            close(null);
        }
    }

    /** 닫히지 않았으면 탭을 다루는 일을 돌린다. 실패하면 오류 종류만 남기고 {@code stopped} 로 닫는다. */
    private void guard(String step, Runnable work) {
        if (closed.get()) {
            return;
        }
        try {
            work.run();
        } catch (RuntimeException ex) {
            String error = ex.getClass().getSimpleName();
            log.debug("browser screen {} failed id={} error={}", step, browserId, error);
            close(STOPPED);
        }
    }

    private void mouse(CdpConnection current, BrowserScreenInput input) {
        Map<String, Object> params = point(input);
        if (params == null) {
            return;
        }
        boolean pressed;
        synchronized (lock) {
            mouseDown = "down".equals(input.action()) || (mouseDown && !"up".equals(input.action()));
            pressed = mouseDown;
        }
        String type = switch (input.action()) {
            case "down" -> "mousePressed";
            case "up" -> "mouseReleased";
            default -> "mouseMoved";
        };
        boolean click = !"mouseMoved".equals(type);
        params.put("type", type);
        params.put("button", click || pressed ? "left" : "none");
        params.put("buttons", pressed ? 1 : 0);
        if (click) {
            params.put("clickCount", 1);
        }
        command(current, "Input.dispatchMouseEvent", params);
    }

    private void wheel(CdpConnection current, BrowserScreenInput input) {
        Map<String, Object> params = point(input);
        if (params != null) {
            params.putAll(Map.of("type", "mouseWheel", "deltaX", 0, "deltaY", input.deltaY()));
            command(current, "Input.dispatchMouseEvent", params);
        }
    }

    /** 비율 좌표를 마지막 프레임의 CSS 픽셀로 바꾼 인자다. 프레임이 아직 없으면 {@code null} 이다. */
    private Map<String, Object> point(BrowserScreenInput input) {
        Viewport size = viewport;
        if (size == null) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("x", input.x() * size.width());
        params.put("y", input.y() * size.height());
        return params;
    }

    /** 특수 키를 누르고 뗀다. 글자를 만드는 Enter 와 Space 는 {@code text} 를 싣는다. */
    private void key(CdpConnection current, String name) {
        Integer keyCode = BrowserScreenInput.KEYS.get(name);
        if (keyCode == null) {
            return;
        }
        boolean space = "Space".equals(name);
        Map<String, Object> up =
                Map.of("type", "keyUp", "key", space ? " " : name, "code", name, "windowsVirtualKeyCode", keyCode);
        Map<String, Object> down = new HashMap<>(up);
        down.put("type", "Enter".equals(name) || space ? "keyDown" : "rawKeyDown");
        if ("Enter".equals(name) || space) {
            down.put("text", space ? " " : "\r");
        }
        command(current, "Input.dispatchKeyEvent", down);
        command(current, "Input.dispatchKeyEvent", up);
    }

    private void back(CdpConnection current) {
        current.send("Page.getNavigationHistory", Map.of())
                .thenCompose(history -> {
                    int index = history.path("currentIndex").asInt();
                    JsonNode entries = history.path("entries");
                    if (index < 1 || !entries.has(index - 1)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return current.send(
                            "Page.navigateToHistoryEntry",
                            Map.of("entryId", entries.get(index - 1).path("id").asInt()));
                })
                .exceptionally(ex -> logFailure("Page.navigateToHistoryEntry"));
    }

    /** 결과를 기다리지 않고 보낸다. 실패는 명령 이름만 남긴다. */
    private void command(CdpConnection current, String method, Map<String, Object> params) {
        current.send(method, params).exceptionally(ex -> logFailure(method));
    }

    private JsonNode logFailure(String method) {
        log.debug("browser screen command failed id={} method={}", browserId, method);
        return null;
    }

    private void schedule(ScheduledFuture<?> task) {
        synchronized (lock) {
            if (closed.get()) {
                task.cancel(false);
                return;
            }
            tasks.add(task);
        }
    }

    /** 마지막 프레임의 화면 크기다. */
    private record Viewport(int width, int height) {}
}
