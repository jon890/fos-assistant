package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.application.port.BrowserUsageHandle;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.testsupport.TestClock;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 에이전트가 쥔 탭의 판정을 본다. 규칙은 {@code docs/features/browser-cowork/design.md} 의 「PR2 에이전트가 쥔 탭」 이다. */
class BrowserAgentTabsTest {

    private static final URI CDP = URI.create("http://192.0.2.10:9999");
    private static final Long BROWSER_ID = 7L;

    private final TestClock clock = new TestClock();
    private FakeCdp cdp;
    private BrowserAgentTabs tabs;


    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-16T00:00:00Z"));
        cdp = new FakeCdp();
        tabs = new BrowserAgentTabs(cdp, clock);
    }


    @Test
    @DisplayName("탭 연결이 열려 있는 동안 쥐고, 같은 탭의 연결이 모두 닫혀야 놓는다")
    void holdsWhileConnectionsAreOpen() {
        BrowserUsageHandle first = tabs.hold(BROWSER_ID, "T1");
        BrowserUsageHandle second = tabs.hold(BROWSER_ID, "T1");

        first.close();
        first.close();
        assertThat(tabs.held(BROWSER_ID, "T1")).isTrue();
        second.close();

        assertThat(tabs.held(BROWSER_ID, "T1")).isFalse();
        assertThat(tabs.held(BROWSER_ID, "T2")).isFalse();
        assertThat(tabs.held(99L, "T1")).isFalse();
    }


    @Test
    @DisplayName("중계로 만든 탭은 연결이 붙기 전에도 30초 동안 쥐고, 지나면 놓는다")
    void holdsCreatedTabForAWhile() {
        tabs.created(BROWSER_ID, "NEW1");

        clock.advance(Duration.ofSeconds(30));
        assertThat(tabs.held(BROWSER_ID, "NEW1")).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(tabs.held(BROWSER_ID, "NEW1")).isFalse();
    }


    @Test
    @DisplayName("만든 탭에 연결이 붙었다가 닫히면 30초 안이어도 놓는다. 넘기기로 남긴 탭이다")
    void releasesCreatedTabWhenItsConnectionCloses() {
        tabs.created(BROWSER_ID, "NEW1");
        BrowserUsageHandle relay = tabs.hold(BROWSER_ID, "NEW1");

        relay.close();

        assertThat(tabs.held(BROWSER_ID, "NEW1")).isFalse();
    }


    @Test
    @DisplayName("쥔 탭이 연 새 탭은 쥔 탭이고, 연 탭을 놓으면 함께 놓인다")
    void holdsTabsOpenedByHeldTabs() {
        BrowserUsageHandle relay = tabs.hold(BROWSER_ID, "T1");
        cdp.openers.put("T2", "T1");
        cdp.openers.put("T3", "HUMAN");
        List<CdpTarget> pages = List.of(page("T2"), page("T1"), page("T3"), page("HUMAN"));

        assertThat(tabs.agentTabs(BROWSER_ID, CDP, pages)).containsExactlyInAnyOrder("T1", "T2");
        relay.close();

        assertThat(tabs.agentTabs(BROWSER_ID, CDP, pages)).isEmpty();
    }


    @Test
    @DisplayName("쥔 탭이 없으면 연 탭을 읽지 않고, 같은 탭은 두 번 읽지 않는다")
    void readsOpenersOnlyWhenNeeded() {
        List<CdpTarget> pages = List.of(page("T1"), page("HUMAN"));
        assertThat(tabs.agentTabs(BROWSER_ID, CDP, pages)).isEmpty();
        assertThat(cdp.openerLookups).hasValue(0);

        BrowserUsageHandle relay = tabs.hold(BROWSER_ID, "T1");
        tabs.agentTabs(BROWSER_ID, CDP, pages);
        tabs.agentTabs(BROWSER_ID, CDP, pages);
        assertThat(cdp.openerLookups).hasValue(1);

        tabs.agentTabs(BROWSER_ID, CDP, List.of(page("T1"), page("HUMAN"), page("T2")));
        assertThat(cdp.openerLookups).hasValue(2);
        relay.close();
    }


    @Test
    @DisplayName("연 탭을 읽지 못하면 그 새 탭을 사람 탭으로 보고 다음 확인에서 다시 읽는다")
    void treatsUnknownOpenerAsHumanTab() {
        BrowserUsageHandle relay = tabs.hold(BROWSER_ID, "T1");
        cdp.openers.put("T2", "T1");
        cdp.failOpeners = true;
        List<CdpTarget> pages = List.of(page("T1"), page("T2"));

        assertThat(tabs.agentTabs(BROWSER_ID, CDP, pages)).containsExactly("T1");
        cdp.failOpeners = false;

        assertThat(tabs.agentTabs(BROWSER_ID, CDP, pages)).containsExactlyInAnyOrder("T1", "T2");
        relay.close();
    }


    private static CdpTarget page(String id) {
        return new CdpTarget(id, "", "https://example.com/" + id);
    }
}
