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
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 로그인 화면 하나다. CDP 연결 하나, 받는 쪽 하나, 사용 핸들 하나를 쥔다. 계약은 {@code docs/backend/user-browser.md} 의
 * 「로그인 화면」 이 갖는다.
 *
 * <p>지금 탭의 screencast 프레임을 받는 대로 ack 하고 {@code frame} 사건으로 넘긴다. 정해 둔 간격마다 탭 목록을 보고, 바뀌면
 * {@code tabs} 사건을 보낸다. 새 탭이 생기면(로그인 팝업) screencast 를 그 탭으로 옮긴다.
 *
 * <p>입력은 정한 종류만 CDP 명령으로 바꾼다. 좌표는 마지막 프레임의 화면 크기를 곱해 CSS 픽셀로 바꾸고, 프레임이 아직 없으면 버린다.
 * 입력 본문은 로그에 남기지 않는다.
 *
 * <p>닫기는 한 번만 일어난다. 예약한 일을 취소하고 CDP 연결과 받는 쪽과 사용 핸들을 닫은 뒤 등록부에서 뺀다.
 */
@Slf4j
public class BrowserScreenSession {

    /** screencast 인자다. 프레임 하나가 수백 KB 다. */
    static final Map<String, Object> SCREENCAST =
            Map.of("format", "jpeg", "quality", 60, "maxWidth", 1280, "maxHeight", 2000);

    /** 다른 화면이 열렸다. */
    static final String REPLACED = "replaced";
    /** 브라우저가 멈췄거나 닿지 않는다. */
    static final String STOPPED = "stopped";
    /** 화면을 열어 둘 수 있는 시간이 지났다. */
    static final String TIMEOUT = "timeout";

    /** 특수 키의 CDP 값이다. 글자를 만드는 키만 {@code text} 를 갖는다. */
    private static final Map<String, KeySpec> KEY_SPECS = Map.of(
            "Enter", new KeySpec("Enter", 13, "\r"),
            "Backspace", new KeySpec("Backspace", 8, null),
            "Tab", new KeySpec("Tab", 9, null),
            "Escape", new KeySpec("Escape", 27, null),
            "ArrowLeft", new KeySpec("ArrowLeft", 37, null),
            "ArrowUp", new KeySpec("ArrowUp", 38, null),
            "ArrowRight", new KeySpec("ArrowRight", 39, null),
            "ArrowDown", new KeySpec("ArrowDown", 40, null),
            "Delete", new KeySpec("Delete", 46, null));

    private static final String ABOUT_BLANK = "about:blank";

    private final Long browserId;
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

    /** 지금 붙은 탭과 그 연결이다. {@link #lock} 으로 지킨다. 탭을 옮길 때마다 세대가 오른다. */
    private CdpConnection connection;

    private String targetId;
    private long generation;
    private boolean mouseDown;
    private List<CdpTarget> tabs = List.of();
    /** 마지막 프레임의 화면 크기(CSS 픽셀)다. 프레임이 아직 없으면 비어 있다. */
    private volatile Viewport viewport;

    BrowserScreenSession(
            Long browserId,
            Long userId,
            URI cdp,
            CdpTargets targets,
            CdpConnector connector,
            BrowserUsageHandle usage,
            BrowserScreenSink sink,
            ScheduledExecutorService scheduler,
            Consumer<BrowserScreenSession> ended) {
        this.browserId = browserId;
        this.userId = userId;
        this.cdp = cdp;
        this.targets = targets;
        this.connector = connector;
        this.usage = usage;
        this.sink = sink;
        this.scheduler = scheduler;
        this.ended = ended;
    }

    Long browserId() {
        return browserId;
    }

    Long userId() {
        return userId;
    }

    boolean closed() {
        return closed.get();
    }

    /**
     * 지금 탭에 붙어 screencast 를 시작한다. 탭이 없으면 빈 탭을 연다. 붙지 못하면 런타임 예외다.
     *
     * @param url 시작 주소. 없으면 지금 주소에 머문다
     */
    void start(String url, Duration timeout, Duration tabInterval) {
        List<CdpTarget> pages = targets.list(cdp);
        if (pages.isEmpty()) {
            pages = List.of(targets.create(cdp, ABOUT_BLANK));
        }
        synchronized (lock) {
            tabs = pages;
        }
        attach(pages.get(0).id(), url);
        sink.onClose(() -> close(null));
        if (!sendTabs()) {
            close(null);
            return;
        }
        schedule(scheduler.schedule(() -> close(TIMEOUT), timeout.toMillis(), TimeUnit.MILLISECONDS));
        schedule(scheduler.scheduleWithFixedDelay(
                this::pollTabs, tabInterval.toMillis(), tabInterval.toMillis(), TimeUnit.MILLISECONDS));
    }

    /**
     * 입력 하나를 CDP 명령으로 보낸다. 명령의 결과는 기다리지 않는다.
     *
     * @throws ApiException 화면이 이미 닫혔으면 {@code BROWSER_SCREEN_CLOSED}
     */
    void input(BrowserScreenInput input) {
        if (closed.get()) {
            throw new ApiException(ErrorCode.BROWSER_SCREEN_CLOSED, "browser screen is closed");
        }
        if (input.kind() == BrowserScreenInput.Kind.TAB) {
            selectTab(input.targetId());
            return;
        }
        CdpConnection current;
        synchronized (lock) {
            current = connection;
        }
        if (current == null) {
            return;
        }
        switch (input.kind()) {
            case MOUSE -> mouse(current, input);
            case WHEEL -> wheel(current, input);
            case KEY -> key(current, input.key());
            case TEXT -> command(current, "Input.insertText", Map.of("text", input.text()));
            case NAVIGATE -> command(current, "Page.navigate", Map.of("url", input.url()));
            case BACK -> back(current);
            case RELOAD -> command(current, "Page.reload", Map.of());
            case RESIZE ->
                command(
                        current,
                        "Emulation.setDeviceMetricsOverride",
                        Map.of(
                                "width",
                                input.width(),
                                "height",
                                input.height(),
                                "deviceScaleFactor",
                                1,
                                "mobile",
                                false));
            case TAB -> {
                // 위에서 따로 다뤘다
            }
        }
    }

    /**
     * 화면을 닫는다. 한 번만 일어난다.
     *
     * @param reason 받는 쪽에 보낼 {@code closed} 의 까닭. 받는 쪽이 먼저 끊겼으면 비운다
     */
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CdpConnection current;
        synchronized (lock) {
            tasks.forEach(task -> task.cancel(false));
            tasks.clear();
            current = connection;
            connection = null;
            generation++;
        }
        if (reason != null) {
            sink.send("closed", Map.of("reason", reason));
        }
        sink.complete();
        if (current != null) {
            current.close();
        }
        usage.close();
        ended.accept(this);
    }

    /** 그 탭에 붙는다. 앞의 연결은 닫는다. 세대가 바뀐 뒤에 온 앞 연결의 사건은 버린다. */
    private void attach(String id, String url) {
        long attached;
        CdpConnection previous;
        synchronized (lock) {
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
        opened.send("Page.enable", Map.of());
        if (url != null) {
            opened.send("Page.navigate", Map.of("url", url));
        }
        opened.send("Page.startScreencast", SCREENCAST).join();
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
        }
        JsonNode params = event.params();
        JsonNode metadata = params.path("metadata");
        int width = (int) Math.round(metadata.path("deviceWidth").asDouble());
        int height = (int) Math.round(metadata.path("deviceHeight").asDouble());
        if (current != null) {
            command(
                    current,
                    "Page.screencastFrameAck",
                    Map.of("sessionId", params.path("sessionId").asInt()));
        }
        if (width > 0 && height > 0) {
            viewport = new Viewport(width, height);
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("data", params.path("data").asString(""));
        frame.put("width", width);
        frame.put("height", height);
        if (!sink.send("frame", frame)) {
            close(null);
        }
    }

    /** 붙은 탭의 연결이 끊겼다. 다른 탭이 남아 있으면 그리로 옮기고, 없으면 화면을 닫는다. */
    private void onConnectionLost(long attached) {
        synchronized (lock) {
            if (attached != generation || closed.get()) {
                return;
            }
        }
        try {
            scheduler.execute(this::recover);
        } catch (RejectedExecutionException ex) {
            close(STOPPED);
        }
    }

    private void recover() {
        if (closed.get()) {
            return;
        }
        try {
            List<CdpTarget> pages = targets.list(cdp);
            if (pages.isEmpty()) {
                close(STOPPED);
                return;
            }
            synchronized (lock) {
                tabs = pages;
            }
            attach(pages.get(0).id(), null);
            if (!sendTabs()) {
                close(null);
            }
        } catch (RuntimeException ex) {
            log.debug(
                    "browser screen lost its tab id={} error={}",
                    browserId,
                    ex.getClass().getSimpleName());
            close(STOPPED);
        }
    }

    /** 탭 목록을 본다. 바뀌면 알리고, 새 탭이 생겼으면 그리로 옮기고, 붙은 탭이 사라졌으면 남은 탭으로 옮긴다. */
    private void pollTabs() {
        if (closed.get()) {
            return;
        }
        try {
            List<CdpTarget> pages = targets.list(cdp);
            if (pages.isEmpty()) {
                close(STOPPED);
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
            if ((next != null || !pages.equals(before)) && !sendTabs()) {
                close(null);
            }
        } catch (RuntimeException ex) {
            log.debug(
                    "browser screen tab check failed id={} error={}",
                    browserId,
                    ex.getClass().getSimpleName());
            close(STOPPED);
        }
    }

    /** 고른 탭으로 옮긴다. 목록에 없는 탭은 무시한다. */
    private void selectTab(String id) {
        boolean known;
        synchronized (lock) {
            known = tabs.stream().anyMatch(tab -> tab.id().equals(id)) && !id.equals(targetId);
        }
        if (!known) {
            return;
        }
        try {
            moveTo(id);
            if (!sendTabs()) {
                close(null);
            }
        } catch (RuntimeException ex) {
            log.debug(
                    "browser screen tab switch failed id={} error={}",
                    browserId,
                    ex.getClass().getSimpleName());
            close(STOPPED);
        }
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

    private boolean sendTabs() {
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
        return sink.send("tabs", views);
    }

    private void mouse(CdpConnection current, BrowserScreenInput input) {
        Viewport size = viewport;
        if (size == null) {
            return;
        }
        String type;
        boolean pressed;
        synchronized (lock) {
            switch (input.action()) {
                case "down" -> {
                    type = "mousePressed";
                    mouseDown = true;
                }
                case "up" -> {
                    type = "mouseReleased";
                    mouseDown = false;
                }
                default -> type = "mouseMoved";
            }
            pressed = mouseDown;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("type", type);
        params.put("x", input.x() * size.width());
        params.put("y", input.y() * size.height());
        boolean click = !"mouseMoved".equals(type);
        params.put("button", click || pressed ? "left" : "none");
        params.put("buttons", pressed ? 1 : 0);
        if (click) {
            params.put("clickCount", 1);
        }
        command(current, "Input.dispatchMouseEvent", params);
    }

    private void wheel(CdpConnection current, BrowserScreenInput input) {
        Viewport size = viewport;
        if (size == null) {
            return;
        }
        command(
                current,
                "Input.dispatchMouseEvent",
                Map.of(
                        "type",
                        "mouseWheel",
                        "x",
                        input.x() * size.width(),
                        "y",
                        input.y() * size.height(),
                        "deltaX",
                        0,
                        "deltaY",
                        input.deltaY()));
    }

    private void key(CdpConnection current, String name) {
        KeySpec spec = KEY_SPECS.get(name);
        if (spec == null) {
            return;
        }
        Map<String, Object> down = new HashMap<>();
        down.put("type", spec.text() == null ? "rawKeyDown" : "keyDown");
        down.put("key", name);
        down.put("code", spec.code());
        down.put("windowsVirtualKeyCode", spec.keyCode());
        if (spec.text() != null) {
            down.put("text", spec.text());
        }
        command(current, "Input.dispatchKeyEvent", down);
        command(
                current,
                "Input.dispatchKeyEvent",
                Map.of("type", "keyUp", "key", name, "code", spec.code(), "windowsVirtualKeyCode", spec.keyCode()));
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

    /** 특수 키 하나의 CDP 값이다. */
    private record KeySpec(String code, int keyCode, String text) {}
}
