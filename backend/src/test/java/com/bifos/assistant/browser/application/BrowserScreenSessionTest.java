package com.bifos.assistant.browser.application;

import static com.bifos.assistant.browser.application.RecordingScreenSink.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.browser.application.model.BrowserScreenInput;
import com.bifos.assistant.browser.application.model.BrowserScreenInput.Kind;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BrowserScreenSessionTest {

    private static final URI CDP = URI.create("http://192.0.2.10:9999");
    private static final Long BROWSER_ID = 7L;

    private FakeCdp cdp;
    private BrowserUsage usage;
    private RecordingScreenSink sink;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean ended = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        cdp = new FakeCdp();
        cdp.pages.add(new CdpTarget("T1", "첫 탭", "https://example.com/"));
        usage = new BrowserUsage();
        sink = new RecordingScreenSink();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        ended.set(false);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        scheduler.shutdownNow();
        assertThat(scheduler.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("열면 지금 탭에 붙어 enable, navigate, startScreencast 차례로 보내고 탭 목록을 알린다")
    void opensInOrder() {
        open(Duration.ofMinutes(30), Duration.ofHours(1), "https://example.com/login");

        assertThat(cdp.attached).containsExactly("T1");
        assertThat(cdp.methods()).containsExactly("Page.enable", "Page.navigate", "Page.startScreencast");
        assertThat(cdp.sent("Page.startScreencast").get(0).params())
                .containsEntry("format", "jpeg")
                .containsEntry("quality", 60)
                .containsEntry("maxWidth", 1280)
                .containsEntry("maxHeight", 2000);
        assertThat(sink.data("tabs"))
                .containsExactly(
                        List.of(Map.of("id", "T1", "title", "첫 탭", "url", "https://example.com/", "active", true)));
        assertThat(usage.inUse(BROWSER_ID)).isTrue();
    }

    @Test
    @DisplayName("탭이 없으면 빈 탭을 열고 시작 주소가 없으면 navigate 를 보내지 않는다")
    void createsBlankTabWhenNone() {
        cdp.pages.clear();

        open(Duration.ofMinutes(30), Duration.ofHours(1), null);

        assertThat(cdp.attached).containsExactly("NEW0");
        assertThat(cdp.methods()).containsExactly("Page.enable", "Page.startScreencast");
    }

    @Test
    @DisplayName("프레임을 받으면 바로 ack 하고 frame 사건으로 그림과 화면 크기를 넘긴다")
    void forwardsAndAcknowledgesFrames() {
        open(Duration.ofMinutes(30), Duration.ofHours(1), null);

        cdp.frame("T1", 390, 844, 5);

        assertThat(cdp.sent("Page.screencastFrameAck").get(0).params()).containsEntry("sessionId", 5);
        assertThat(sink.data("frame")).containsExactly(Map.of("data", "AAAA", "width", 390, "height", 844));
    }

    @Test
    @DisplayName("프레임 전의 좌표 입력은 버리고 프레임 뒤에는 비율에 화면 크기를 곱해 보낸다")
    void convertsRatiosAfterFirstFrame() {
        BrowserScreenSession session = open(Duration.ofMinutes(30), Duration.ofHours(1), null);

        session.input(mouse("down", 0.5, 0.25));
        assertThat(cdp.sent("Input.dispatchMouseEvent")).isEmpty();

        cdp.frame("T1", 400, 800, 1);
        session.input(mouse("down", 0.5, 0.25));
        session.input(mouse("move", 0.75, 0.5));
        session.input(mouse("up", 0.75, 0.5));
        session.input(new BrowserScreenInput(Kind.WHEEL, null, 0.5, 0.5, -300, null, null, null, null, 0, 0));

        List<FakeCdp.Sent> mouse = cdp.sent("Input.dispatchMouseEvent");
        assertThat(mouse.get(0).params())
                .containsEntry("type", "mousePressed")
                .containsEntry("x", 200.0)
                .containsEntry("y", 200.0)
                .containsEntry("button", "left")
                .containsEntry("clickCount", 1);
        assertThat(mouse.get(1).params())
                .containsEntry("type", "mouseMoved")
                .containsEntry("x", 300.0)
                .containsEntry("buttons", 1);
        assertThat(mouse.get(2).params()).containsEntry("type", "mouseReleased");
        assertThat(mouse.get(3).params())
                .containsEntry("type", "mouseWheel")
                .containsEntry("y", 400.0)
                .containsEntry("deltaY", -300);
    }

    @Test
    @DisplayName("키, 글자, 주소, 뒤로, 새로고침, 크기 입력을 정한 CDP 명령으로 바꾼다")
    void mapsInputsToCommands() {
        BrowserScreenSession session = open(Duration.ofMinutes(30), Duration.ofHours(1), null);

        session.input(input(Kind.KEY, "Enter", null, null));
        session.input(input(Kind.TEXT, null, "안녕하세요", null));
        session.input(input(Kind.NAVIGATE, null, null, "https://example.com/next"));
        session.input(input(Kind.BACK, null, null, null));
        session.input(input(Kind.RELOAD, null, null, null));
        session.input(new BrowserScreenInput(Kind.RESIZE, null, 0, 0, 0, null, null, null, null, 390, 844));

        List<FakeCdp.Sent> keys = cdp.sent("Input.dispatchKeyEvent");
        assertThat(keys.get(0).params())
                .containsEntry("type", "keyDown")
                .containsEntry("text", "\r")
                .containsEntry("windowsVirtualKeyCode", 13);
        assertThat(keys.get(1).params()).containsEntry("type", "keyUp");
        assertThat(cdp.sent("Input.insertText").get(0).params()).containsEntry("text", "안녕하세요");
        assertThat(cdp.sent("Page.navigate").get(0).params()).containsEntry("url", "https://example.com/next");
        assertThat(cdp.sent("Page.navigateToHistoryEntry").get(0).params()).containsEntry("entryId", 11);
        assertThat(cdp.sent("Page.reload")).hasSize(1);
        assertThat(cdp.sent("Emulation.setDeviceMetricsOverride").get(0).params())
                .containsEntry("width", 390)
                .containsEntry("height", 844)
                .containsEntry("deviceScaleFactor", 1)
                .containsEntry("mobile", false);
    }

    @Test
    @DisplayName("새 탭이 생기면 screencast 를 그 탭으로 옮기고 탭 목록을 다시 알린다")
    void movesToNewTab() throws InterruptedException {
        open(Duration.ofMinutes(30), Duration.ofMillis(20), null);

        cdp.pages.add(new CdpTarget("T2", "로그인", "https://example.com/popup"));

        await(() -> cdp.attached.contains("T2") && sink.data("tabs").size() >= 2);
        assertThat(cdp.activated).contains("T2");
        assertThat(cdp.closedByUs("T1")).isTrue();
        assertThat(cdp.sent("Page.startScreencast"))
                .extracting(FakeCdp.Sent::targetId)
                .containsExactly("T1", "T2");
        assertThat(sink.data("tabs").get(1))
                .isEqualTo(List.of(
                        Map.of("id", "T1", "title", "첫 탭", "url", "https://example.com/", "active", false),
                        Map.of("id", "T2", "title", "로그인", "url", "https://example.com/popup", "active", true)));
    }

    @Test
    @DisplayName("고른 탭으로 옮기고 목록에 없는 탭은 무시한다")
    @SuppressWarnings("unchecked")
    void switchesToChosenTab() {
        cdp.pages.add(new CdpTarget("T2", "둘째", "https://example.com/b"));
        BrowserScreenSession session = open(Duration.ofMinutes(30), Duration.ofHours(1), null);

        session.input(input(Kind.TAB, null, null, null, "UNKNOWN"));
        session.input(input(Kind.TAB, null, null, null, "T2"));

        assertThat(cdp.attached).containsExactly("T1", "T2");
        List<Object> tabs = sink.data("tabs");
        Object expected = Map.of("id", "T2", "title", "둘째", "url", "https://example.com/b", "active", true);
        assertThat((List<Object>) tabs.get(tabs.size() - 1)).contains(expected);
    }

    @Test
    @DisplayName("붙은 탭이 끊기면 남은 탭으로 옮기고 남은 탭이 없으면 stopped 로 닫는다")
    void recoversOrClosesWhenTabIsLost() throws InterruptedException {
        cdp.pages.add(new CdpTarget("T2", "둘째", "https://example.com/b"));
        open(Duration.ofMinutes(30), Duration.ofHours(1), null);

        cdp.pages.remove(0);
        cdp.drop("T1");
        await(() -> cdp.attached.contains("T2"));

        cdp.pages.clear();
        cdp.drop("T2");
        await(() -> sink.completed);
        assertThat(sink.data("closed")).containsExactly(Map.of("reason", "stopped"));
        assertThat(ended).isTrue();
    }

    @Test
    @DisplayName("열어 둘 수 있는 시간이 지나면 timeout 으로 닫고 사용 핸들과 연결을 닫는다")
    void closesOnTimeout() throws InterruptedException {
        BrowserScreenSession session = open(Duration.ofMillis(50), Duration.ofHours(1), null);

        await(() -> sink.completed);

        assertThat(sink.data("closed")).containsExactly(Map.of("reason", "timeout"));
        assertThat(usage.inUse(BROWSER_ID)).isFalse();
        assertThat(cdp.closedByUs("T1")).isTrue();
        assertThat(ended).isTrue();
        assertThatThrownBy(() -> session.input(input(Kind.RELOAD, null, null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_SCREEN_CLOSED));
    }

    @Test
    @DisplayName("받는 쪽이 끊기거나 쓰기가 실패하면 closed 를 보내지 않고 닫는다")
    void closesQuietlyWhenSinkEnds() throws InterruptedException {
        open(Duration.ofMinutes(30), Duration.ofHours(1), null);
        sink.failing = true;

        cdp.frame("T1", 400, 800, 1);

        await(() -> ended.get());
        assertThat(sink.data("closed")).isEmpty();
        assertThat(usage.inUse(BROWSER_ID)).isFalse();
    }

    @Test
    @DisplayName("주석 줄 쓰기가 실패하면 closed 없이 닫고 사용 핸들을 놓는다")
    void closesWhenHeartbeatFails() throws InterruptedException {
        open(Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofMillis(20), null);

        sink.pingFailing = true;

        await(() -> ended.get());
        assertThat(sink.data("closed")).isEmpty();
        assertThat(usage.inUse(BROWSER_ID)).isFalse();
    }

    @Test
    @DisplayName("앞 탭 옮기기의 연결을 뒤 탭 옮기기가 닫아 명령이 실패해도 화면을 닫지 않는다")
    void ignoresFailureOfSupersededAttach() throws InterruptedException {
        cdp.pages.add(new CdpTarget("T2", "둘째", "https://example.com/b"));
        BrowserScreenSession session = open(Duration.ofMinutes(30), Duration.ofHours(1), null);
        cdp.holdScreencast = "T2";

        Thread first = Thread.ofPlatform().start(() -> session.input(input(Kind.TAB, null, null, null, "T2")));
        await(() -> cdp.screencastHeld());
        session.input(input(Kind.TAB, null, null, null, "T1"));
        cdp.releaseScreencast();
        first.join(2000);

        assertThat(first.isAlive()).isFalse();
        assertThat(session.closed()).isFalse();
        assertThat(sink.data("closed")).isEmpty();
        assertThat(cdp.attached).containsExactly("T1", "T2", "T1");
        assertThat(cdp.closedByUs("T2")).isTrue();
    }

    @Test
    @DisplayName("프레임은 받는 쪽에 넘긴 뒤에 ack 한다")
    void acknowledgesAfterForwarding() throws InterruptedException {
        open(Duration.ofMinutes(30), Duration.ofHours(1), null);
        sink.gate = new CountDownLatch(1);

        Thread reader = Thread.ofPlatform().start(() -> cdp.frame("T1", 400, 800, 3));
        await(() -> sink.writing);
        assertThat(cdp.sent("Page.screencastFrameAck")).isEmpty();

        sink.gate.countDown();
        reader.join(2000);
        assertThat(cdp.sent("Page.screencastFrameAck")).hasSize(1);
    }

    @Test
    @DisplayName("받는 쪽 쓰기가 막혀 있어도 닫기는 closed 를 건너뛰고 바로 돌아온다")
    void closesWithoutWaitingForBlockedWrite() throws InterruptedException {
        BrowserScreenSession session = open(Duration.ofMinutes(30), Duration.ofHours(1), null);
        CountDownLatch gate = new CountDownLatch(1);
        sink.gate = gate;
        Thread reader = Thread.ofPlatform().start(() -> cdp.frame("T1", 400, 800, 3));
        await(() -> sink.writing);

        Thread closer = Thread.ofPlatform().start(() -> session.close(BrowserScreenSession.REPLACED));
        closer.join(2000);

        assertThat(closer.isAlive()).isFalse();
        assertThat(sink.completed).isTrue();
        assertThat(usage.inUse(BROWSER_ID)).isFalse();
        gate.countDown();
        reader.join(2000);
        assertThat(sink.data("closed")).isEmpty();
        assertThat(cdp.sent("Page.screencastFrameAck")).isEmpty();
    }

    @Test
    @DisplayName("프레임 없이 연이어 세 번까지만 다시 잇고 넘으면 stopped 로 닫는다")
    void stopsAfterReconnectLimit() throws InterruptedException {
        open(Duration.ofMinutes(30), Duration.ofHours(1), null);

        for (int attempt = 1; attempt <= 3; attempt++) {
            dropAndAwaitReattach(attempt + 1);
        }
        cdp.frame("T1", 400, 800, 1);
        dropAndAwaitReattach(5);
        assertThat(sink.data("closed")).isEmpty();

        // 프레임 뒤 첫 끊김이 다시 1회째다. 두 번 더 이은 뒤 네 번째 끊김에서 닫는다
        dropAndAwaitReattach(6);
        dropAndAwaitReattach(7);
        cdp.drop("T1");

        await(() -> sink.completed);
        assertThat(sink.data("closed")).containsExactly(Map.of("reason", "stopped"));
        assertThat(cdp.attached).hasSize(7);
    }

    @Test
    @DisplayName("마지막 크기 입력을 기억했다가 탭을 옮기면 screencast 전에 새 탭에 다시 보낸다")
    void reappliesResizeAfterTabSwitch() {
        cdp.pages.add(new CdpTarget("T2", "둘째", "https://example.com/b"));
        BrowserScreenSession session = open(Duration.ofMinutes(30), Duration.ofHours(1), null);

        session.input(new BrowserScreenInput(Kind.RESIZE, null, 0, 0, 0, null, null, null, null, 390, 844));
        session.input(input(Kind.TAB, null, null, null, "T2"));

        List<FakeCdp.Sent> resized = cdp.sent("Emulation.setDeviceMetricsOverride");
        assertThat(resized).extracting(FakeCdp.Sent::targetId).containsExactly("T1", "T2");
        assertThat(resized.get(1).params()).containsEntry("width", 390).containsEntry("height", 844);
        assertThat(cdp.sent.stream()
                        .filter(command -> command.targetId().equals("T2"))
                        .map(FakeCdp.Sent::method))
                .containsExactly("Page.enable", "Emulation.setDeviceMetricsOverride", "Page.startScreencast");
    }

    /** 붙은 탭을 끊고 다시 붙어 screencast 를 시작하기까지 기다린다. 붙기가 끝나기 전에 다시 끊으면 경합이 된다. */
    private void dropAndAwaitReattach(int screencasts) throws InterruptedException {
        cdp.drop("T1");
        await(() -> cdp.sent("Page.startScreencast").size() == screencasts);
    }

    private BrowserScreenSession open(Duration timeout, Duration tabInterval, String url) {
        return open(timeout, tabInterval, Duration.ofHours(1), url);
    }

    private BrowserScreenSession open(Duration timeout, Duration tabInterval, Duration heartbeat, String url) {
        BrowserScreenSession session = new BrowserScreenSession(
                BROWSER_ID, 201L, CDP, cdp, cdp, usage.open(BROWSER_ID), sink, scheduler, closed -> ended.set(true));
        session.start(url, timeout, tabInterval, heartbeat);
        return session;
    }

    private static BrowserScreenInput mouse(String action, double x, double y) {
        return new BrowserScreenInput(Kind.MOUSE, action, x, y, 0, null, null, null, null, 0, 0);
    }

    private static BrowserScreenInput input(Kind kind, String key, String text, String url) {
        return input(kind, key, text, url, null);
    }

    private static BrowserScreenInput input(Kind kind, String key, String text, String url, String targetId) {
        return new BrowserScreenInput(kind, null, 0, 0, 0, key, text, url, targetId, 0, 0);
    }
}
