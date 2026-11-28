package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.application.dto.BrowserEndpoint;
import com.bifos.assistant.browser.application.dto.TabCloseResult;
import com.bifos.assistant.browser.application.dto.TabRef;
import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.browser.domain.CdpTargets;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 에이전트의 탭 목록·열기·닫기가 요청자의 브라우저에만 닿는지 본다(docs/features/agent-browser/design.md 의 「PR1 도구」). */
class BrowserTabControlTest {

    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
    private static final Long OWNER = 7L;
    private static final Long OTHER = 8L;
    private static final URI OWNER_CDP = URI.create("http://192.0.2.10:9222");
    private static final URI OTHER_CDP = URI.create("http://192.0.2.20:9222");

    private final FakeCdp ownerCdp = new FakeCdp();
    private final FakeCdp otherCdp = new FakeCdp();
    private final List<URI> called = new ArrayList<>();
    private final Set<String> kept = new HashSet<>();
    private final UserBrowserRepository repository = mock(UserBrowserRepository.class);
    private final UserBrowserService browsers = mock(UserBrowserService.class);
    private final BrowserRuntime runtime = mock(BrowserRuntime.class);
    private final BrowserHumanHold holds = mock(BrowserHumanHold.class);
    private BrowserAgentTabs agentTabs;
    private BrowserTabControl control;


    @BeforeEach
    void setUp() {
        running(OWNER, 11L, "c-owner", OWNER_CDP);
        running(OTHER, 12L, "c-other", OTHER_CDP);
        ownerCdp.pages.add(new CdpTarget("A1", "내 메일", "https://mail.example.com/inbox"));
        ownerCdp.pages.add(new CdpTarget("A2", "뉴스", "https://news.example.com/"));
        otherCdp.pages.add(new CdpTarget("B1", "남의 은행", "https://bank.example.com/"));
        when(browsers.ensureRunning(OWNER)).thenReturn(new BrowserEndpoint(11L, OWNER_CDP));
        agentTabs = new BrowserAgentTabs(ownerCdp, Clock.systemUTC());
        control = new BrowserTabControl(repository, browsers, runtime, routed(), agentTabs,
            (userId, tab, since) -> kept.contains(tab), holds, new BrowserUsage());
    }


    @Test
    @DisplayName("합성 사용자 둘은 각자 자기 브라우저의 탭만 보고, 남의 탭 번호로 닫으면 남의 브라우저를 부르지 않고 건너뛴다")
    void twoUsersReachOnlyTheirOwnTabs() {
        assertThat(control.list(OWNER).tabs()).extracting(CdpTarget::id).containsExactly("A1", "A2");
        assertThat(control.list(OTHER).tabs()).extracting(CdpTarget::id).containsExactly("B1");
        called.clear();

        TabCloseResult result = control.close(OWNER, List.of(new TabRef("B1", BrowserTabControl.urlHash("https://bank.example.com/"))));

        assertThat(result).isEqualTo(new TabCloseResult(0, 1));
        assertThat(called).containsOnly(OWNER_CDP);
        assertThat(otherCdp.pages).extracting(CdpTarget::id).containsExactly("B1");
        assertThat(otherCdp.closed).isEmpty();
    }


    @Test
    @DisplayName("에이전트가 쥔 탭과 넘기기 카드가 지키는 탭은 목록에 없고 닫지 않는다")
    void hidesAndKeepsHeldAndKeptTabs() {
        agentTabs.created(11L, "A1");
        kept.add("A2");
        ownerCdp.pages.add(new CdpTarget("A3", "블로그", "https://blog.example.com/"));

        assertThat(control.list(OWNER).tabs()).extracting(CdpTarget::id).containsExactly("A3");
        TabCloseResult result = control.close(OWNER, List.of(
            new TabRef("A1", BrowserTabControl.urlHash("https://mail.example.com/inbox")),
            new TabRef("A2", BrowserTabControl.urlHash("https://news.example.com/")),
            new TabRef("A3", BrowserTabControl.urlHash("https://blog.example.com/"))));

        assertThat(result).isEqualTo(new TabCloseResult(1, 2));
        assertThat(ownerCdp.closed).containsExactly("A3");
    }


    @Test
    @DisplayName("카드를 띄운 뒤 다른 페이지로 간 탭은 닫지 않는다")
    void skipsTabThatMovedAway() {
        TabCloseResult result = control.close(OWNER, List.of(
            new TabRef("A1", BrowserTabControl.urlHash("https://mail.example.com/old")),
            new TabRef("A2", BrowserTabControl.urlHash("https://news.example.com/"))));

        assertThat(result).isEqualTo(new TabCloseResult(1, 1));
        assertThat(ownerCdp.closed).containsExactly("A2");
    }


    @Test
    @DisplayName("꺼진 브라우저는 켜지 않고 빈 목록이며 닫기는 모두 건너뛴다")
    void doesNotStartStoppedBrowser() {
        UserBrowser stopped = UserBrowser.create(OWNER, "p", NOW);
        ReflectionTestUtils.setField(stopped, "id", 11L);
        when(repository.findByUserId(OWNER)).thenReturn(Optional.of(stopped));

        assertThat(control.list(OWNER).running()).isFalse();
        assertThat(control.list(OWNER).tabs()).isEmpty();
        assertThat(control.close(OWNER, List.of(new TabRef("A1", "x")))).isEqualTo(new TabCloseResult(0, 1));
        verify(browsers, never()).ensureRunning(anyLong());
        assertThat(called).isEmpty();
    }


    @Test
    @DisplayName("열기는 꺼진 브라우저를 켜고 새 탭을 연다")
    void opensTabAfterStarting() {
        String id = control.open(OWNER, "https://example.com/reserve");

        verify(browsers).ensureRunning(OWNER);
        assertThat(ownerCdp.pages).extracting(CdpTarget::id).contains(id);
        assertThat(called).containsOnly(OWNER_CDP);
    }


    @Test
    @DisplayName("웹 주소가 아니거나 내부 주소면 열지 않는다")
    void rejectsNonPublicAddresses() {
        for (String url : List.of("file:///etc/passwd", "javascript:alert(1)", "http://localhost:8080/", "http://intranet/",
            "http://127.0.0.1/", "http://10.0.0.5/", "http://192.168.1.1/", "http://169.254.169.254/latest", "http://[::1]/",
            "http://[fd00::1]/", "http://printer.local/", "http://localhost./", "http://metadata.google.internal./",
            "http://0x7f.0.0.1/", "http://0177.0.0.1/", "http://2130706433/", "http://127.1/", "")) {
            assertThatThrownBy(() -> control.open(OWNER, url)).as(url).isInstanceOf(IllegalArgumentException.class);
        }
        verify(browsers, never()).ensureRunning(anyLong());
        BrowserTabControl.requireWebAddress("http://8.8.8.8/");
        BrowserTabControl.requireWebAddress("https://www.example.com./path");
    }


    @Test
    @DisplayName("사람이 「직접 할게요」 로 쥐고 있으면 열지도 닫지도 않는다")
    void refusesWhileHumanHolds() {
        when(holds.held(OWNER)).thenReturn(true);

        assertThatThrownBy(() -> control.open(OWNER, "https://example.com/"))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_HUMAN_HELD));
        assertThatThrownBy(() -> control.close(OWNER, List.of(new TabRef("A2", BrowserTabControl.urlHash("https://news.example.com/")))))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_HUMAN_HELD));
        assertThat(ownerCdp.closed).isEmpty();
    }


    @Test
    @DisplayName("지킬 탭을 판정하지 못하면 그 탭을 목록에서 빼고 닫지 않는다")
    void keepsTabWhenKeptCheckFails() {
        control = new BrowserTabControl(repository, browsers, runtime, routed(), agentTabs,
            (userId, tab, since) -> {
                throw new IllegalStateException("down");
            }, holds, new BrowserUsage());

        assertThat(control.list(OWNER).tabs()).isEmpty();
    }


    private void running(Long userId, Long browserId, String containerId, URI cdp) {
        UserBrowser browser = UserBrowser.create(userId, "p" + userId, NOW);
        browser.beginStart(NOW);
        browser.markRunning(containerId, NOW);
        ReflectionTestUtils.setField(browser, "id", browserId);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(browser));
        when(runtime.cdpAddress(containerId)).thenReturn(Optional.of(cdp));
    }


    /** 주소마다 그 사용자의 가짜 Chrome 으로 보낸다. 부른 주소를 적는다. */
    private CdpTargets routed() {
        Map<URI, FakeCdp> byAddress = Map.of(OWNER_CDP, ownerCdp, OTHER_CDP, otherCdp);
        return new CdpTargets() {
            @Override
            public List<CdpTarget> list(URI cdp) {
                called.add(cdp);
                return byAddress.get(cdp).list(cdp);
            }


            @Override
            public CdpTarget create(URI cdp, String url) {
                called.add(cdp);
                return byAddress.get(cdp).create(cdp, url);
            }


            @Override
            public void activate(URI cdp, String id) {
                called.add(cdp);
                byAddress.get(cdp).activate(cdp, id);
            }


            @Override
            public void close(URI cdp, String id) {
                called.add(cdp);
                byAddress.get(cdp).close(cdp, id);
            }
        };
    }
}
