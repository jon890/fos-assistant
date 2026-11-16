package com.bifos.assistant.browser.application;

import static com.bifos.assistant.browser.application.RecordingScreenSink.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.application.dto.BrowserScreenInput;
import com.bifos.assistant.browser.application.dto.BrowserScreenInput.Kind;
import com.bifos.assistant.browser.application.port.BrowserUsageHandle;
import com.bifos.assistant.browser.domain.CdpTarget;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 에이전트가 쥔 탭을 화면이 보기 전용으로 다루는지 본다. 규칙은 {@code docs/features/browser-cowork/design.md} 의 「PR2 에이전트가 쥔
 * 탭」 이다.
 */
class BrowserScreenSessionAgentTabTest {

    private static final URI CDP = URI.create("http://192.0.2.10:9999");
    private static final Long BROWSER_ID = 7L;
    private static final Duration FAST = Duration.ofMillis(20);
    private static final Duration SLOW = Duration.ofHours(1);

    private FakeCdp cdp;
    private BrowserAgentTabs agentTabs;
    private RecordingScreenSink sink;
    private ScheduledExecutorService scheduler;


    @BeforeEach
    void setUp() {
        cdp = new FakeCdp();
        cdp.pages.add(new CdpTarget("T1", "첫 탭", "https://example.com/"));
        agentTabs = new BrowserAgentTabs(cdp, Clock.systemUTC());
        sink = new RecordingScreenSink();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }


    @AfterEach
    void tearDown() throws InterruptedException {
        scheduler.shutdownNow();
        assertThat(scheduler.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }


    @Test
    @DisplayName("쥔 탭에는 누르기, 휠, 스크롤, 키, 글자, 주소, 뒤로, 새로 고침, 확인 창 입력을 보내지 않고 탭 옮기기는 된다")
    void dropsInputsOnAgentTab() {
        cdp.pages.add(new CdpTarget("T2", "사람", "https://example.com/b"));
        BrowserUsageHandle relay = agentTabs.hold(BROWSER_ID, "T1");
        BrowserScreenSession session = open(SLOW, null);
        cdp.frame("T1", 400, 800, 1);
        int before = cdp.sent.size();

        session.input(new BrowserScreenInput(Kind.MOUSE, "down", 0.5, 0.5, 0, null, null, null, null, 0, 0));
        session.input(new BrowserScreenInput(Kind.WHEEL, null, 0.5, 0.5, -300, null, null, null, null, 0, 0));
        session.input(new BrowserScreenInput(Kind.SCROLL, "top", 0, 0, 0, null, null, null, null, 0, 0));
        session.input(input(Kind.KEY, "Enter", null, null, null));
        session.input(input(Kind.TEXT, null, "비밀", null, null));
        session.input(input(Kind.NAVIGATE, null, null, "https://example.com/x", null));
        session.input(input(Kind.BACK, null, null, null, null));
        session.input(input(Kind.RELOAD, null, null, null, null));
        session.input(new BrowserScreenInput(Kind.DIALOG, "accept", 0, 0, 0, null, null, null, null, 0, 0));

        assertThat(cdp.sent.subList(before, cdp.sent.size())).isEmpty();
        session.input(input(Kind.TAB, null, null, null, "T2"));
        assertThat(cdp.attached).containsExactly("T1", "T2");
        relay.close();
    }


    @Test
    @DisplayName("사람 탭은 에이전트가 다른 탭을 쥔 동안에도 조작된다")
    void humanTabStillWorks() {
        BrowserUsageHandle relay = agentTabs.hold(BROWSER_ID, "AGENT");
        BrowserScreenSession session = open(SLOW, null);

        session.input(input(Kind.TEXT, null, "글자", null, null));
        session.input(input(Kind.RELOAD, null, null, null, null));

        assertThat(cdp.methods()).contains("Input.insertText", "Page.reload");
        relay.close();
    }


    @Test
    @DisplayName("tabs 에 에이전트가 쥐었는지 싣고, 놓이면 목록을 다시 보내며 조작된다")
    void reportsAgentFlagAndRelease() throws InterruptedException {
        BrowserUsageHandle relay = agentTabs.hold(BROWSER_ID, "T1");
        BrowserScreenSession session = open(FAST, null);
        assertThat(sink.data("tabs").get(0))
            .isEqualTo(List.of(Map.of("id", "T1", "title", "첫 탭", "url", "https://example.com/", "active", true, "agent", true)));

        relay.close();
        await(() -> sink.data("tabs").size() >= 2);

        assertThat(sink.data("tabs").get(1))
            .isEqualTo(List.of(Map.of("id", "T1", "title", "첫 탭", "url", "https://example.com/", "active", true, "agent", false)));
        session.input(input(Kind.RELOAD, null, null, null, null));
        assertThat(cdp.methods()).contains("Page.reload");
    }


    @Test
    @DisplayName("사람 탭을 보는 중 에이전트가 연 새 탭으로 옮기지 않고 목록만 알린다")
    void staysOnHumanTabWhenAgentOpensTab() throws InterruptedException {
        open(FAST, null);

        agentTabs.created(BROWSER_ID, "AGENT");
        cdp.pages.add(new CdpTarget("AGENT", "에이전트", "https://example.com/a"));
        await(() -> sink.data("tabs").size() >= 2);

        assertThat(cdp.attached).containsExactly("T1");
        assertThat(cdp.activated).doesNotContain("AGENT");
    }


    @Test
    @DisplayName("사람 탭의 팝업과 에이전트 탭을 보는 중 생긴 새 탭은 지금처럼 따라간다")
    void followsHumanPopupAndAgentTabs() throws InterruptedException {
        BrowserUsageHandle relay = agentTabs.hold(BROWSER_ID, "OTHER");
        open(FAST, null);
        cdp.pages.add(new CdpTarget("POPUP", "로그인", "https://example.com/popup"));
        await(() -> cdp.attached.contains("POPUP"));
        relay.close();

        BrowserUsageHandle watched = agentTabs.hold(BROWSER_ID, "POPUP");
        agentTabs.created(BROWSER_ID, "NEXT");
        cdp.pages.add(new CdpTarget("NEXT", "주문서", "https://example.com/order"));
        await(() -> cdp.attached.contains("NEXT"));
        watched.close();
    }


    @Test
    @DisplayName("주소를 주고 열 때 첫 탭이 쥔 탭이면 그 탭을 옮기지 않고 새 탭에서 연다")
    void opensUrlInNewTabWhenFirstTabIsAgents() {
        BrowserUsageHandle relay = agentTabs.hold(BROWSER_ID, "T1");

        open(SLOW, "https://example.com/login");

        assertThat(cdp.attached).containsExactly("NEW1");
        assertThat(cdp.sent("Page.navigate")).extracting(FakeCdp.Sent::targetId).containsExactly("NEW1");
        relay.close();
    }


    @Test
    @DisplayName("주소를 주고 열 때 첫 탭이 쥔 탭이 연 팝업이면 연결이 붙기 전이어도 새 탭에서 연다")
    void opensUrlInNewTabWhenFirstTabIsAgentPopup() {
        BrowserUsageHandle relay = agentTabs.hold(BROWSER_ID, "T1");
        cdp.pages.add(0, new CdpTarget("POPUP", "바로구매", "https://example.com/order"));
        cdp.openers.put("POPUP", "T1");

        open(SLOW, "https://example.com/login");

        assertThat(cdp.attached).containsExactly("NEW2");
        assertThat(cdp.sent("Page.navigate")).extracting(FakeCdp.Sent::targetId).containsExactly("NEW2");
        relay.close();
    }


    @Test
    @DisplayName("쥔 탭에는 화면 크기 덮어쓰기를 보내지 않고, 놓이면 기억한 크기를 보낸다")
    void withholdsResizeOnAgentTab() throws InterruptedException {
        BrowserUsageHandle relay = agentTabs.hold(BROWSER_ID, "T1");
        BrowserScreenSession session = open(FAST, null);

        session.input(new BrowserScreenInput(Kind.RESIZE, null, 0, 0, 0, null, null, null, null, 390, 844));
        Thread.sleep(300);
        assertThat(cdp.sent("Emulation.setDeviceMetricsOverride")).isEmpty();

        relay.close();
        await(() -> !cdp.sent("Emulation.setDeviceMetricsOverride").isEmpty());
        assertThat(cdp.sent("Emulation.setDeviceMetricsOverride").get(0).params()).containsEntry("width", 390);
    }


    private BrowserScreenSession open(Duration tabInterval, String url) {
        BrowserScreenSession session = new BrowserScreenSession(
            BROWSER_ID, 201L, CDP, cdp, cdp, new BrowserUsage().open(BROWSER_ID), sink, agentTabs, scheduler, closed -> {
            });
        session.start(url, Duration.ofMinutes(30), tabInterval, SLOW);
        return session;
    }


    private static BrowserScreenInput input(Kind kind, String key, String text, String url, String targetId) {
        return new BrowserScreenInput(kind, null, 0, 0, 0, key, text, url, targetId, 0, 0);
    }
}
