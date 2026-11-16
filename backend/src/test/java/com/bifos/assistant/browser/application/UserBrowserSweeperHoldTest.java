package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.FakeBrowserRuntime;
import com.bifos.assistant.testsupport.TestClock;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 사람 잠금의 10분 풀림과 잠긴 브라우저의 자동 중지를 실제 DB 로 본다. 뜻은 spec 의 REQ1, REQ7 과 설계의 「PR3 사람 잠금」 이다. */
@BackendIntegrationTest
class UserBrowserSweeperHoldTest {

    private static final Long USER = 201L;
    private static final URI CDP = URI.create("http://192.0.2.10:9999");

    @Autowired
    UserBrowserRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private BrowserScreens screens;
    private BrowserHumanHold holds;
    private UserBrowserService service;
    private UserBrowserSweeper sweeper;
    /** 점검만 쓰는 시계다. 잠근 시각은 실제 시각이다. */
    private final TestClock sweepClock = new TestClock();


    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_browser");
        FakeCdp cdp = new FakeCdp();
        cdp.pages.add(new CdpTarget("T1", "첫 탭", "https://example.com/"));
        BrowserAgentTabs agentTabs = new BrowserAgentTabs(cdp, Clock.systemUTC());
        holds = new BrowserHumanHold(repository, userId -> false, agentTabs, Clock.systemUTC(), transactions);
        BrowserUsage usage = new BrowserUsage();
        screens = new BrowserScreens(cdp, cdp, usage, agentTabs, holds, () -> Duration.ofMinutes(30), Duration.ofHours(1));
        LiveProperties<BrowserProperties> properties = LiveProperties.fixed(BrowserProperties.class, properties());
        FakeBrowserRuntime runtime = new FakeBrowserRuntime();
        service = UserBrowserServiceTestFactory.create(
            repository, runtime, profiles(), address -> true, properties, Clock.systemUTC(), screens, holds);
        sweeper = new UserBrowserSweeper(
            service, screens, repository, runtime, usage, userId -> true, (userId, since) -> false, holds, properties,
            sweepClock);
    }


    @AfterEach
    void tearDown() {
        screens.close();
        jdbc.update("DELETE FROM user_browser");
    }


    @Test
    @DisplayName("화면 없이 넘겨받은 지 10분이 지나면 잠금을 풀고, 지나지 않았으면 둔다")
    void releasesHoldTenMinutesAfterScreensClosed() {
        Long id = running();
        holds.takeOver(USER, null);

        sweeper.sweep();
        assertThat(holds.held(USER)).isTrue();

        sweepClock.advance(Duration.ofMinutes(9));
        sweeper.sweep();
        assertThat(holds.held(USER)).isTrue();

        sweepClock.advance(Duration.ofMinutes(2));
        sweeper.sweep();
        assertThat(holds.held(USER)).isFalse();
    }


    @Test
    @DisplayName("화면이 열려 있으면 오래 쥐었어도 풀지 않고, 닫힌 뒤부터 10분을 센다")
    void keepsHoldWhileScreenIsOpen() {
        Long id = running();
        holds.takeOver(USER, null);
        ageHold(id, Duration.ofMinutes(30));
        screens.open(id, USER, CDP, null, new RecordingScreenSink());

        sweeper.sweep();
        assertThat(holds.held(USER)).isTrue();

        screens.close(id);
        sweepClock.advance(Duration.ofMinutes(9));
        sweeper.sweep();
        assertThat(holds.held(USER)).isTrue();

        sweepClock.advance(Duration.ofMinutes(2));
        sweeper.sweep();
        assertThat(holds.held(USER)).isFalse();
    }


    @Test
    @DisplayName("사람이 쥔 브라우저는 10분 유휴에 끄지 않는다")
    void doesNotStopHeldBrowserAfterIdle() {
        Long id = running();
        holds.takeOver(USER, null);
        sweepClock.set(Instant.now());
        jdbc.update("UPDATE user_browser SET last_active_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minus(Duration.ofMinutes(15))), id);

        sweeper.stopIdle();

        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(UserBrowserStatus.RUNNING);
    }


    private Long running() {
        Long id = service.create(USER).id();
        service.start(USER);
        return id;
    }


    private void ageHold(Long id, Duration age) {
        // 켠 시각도 함께 옮겨 이번 켜기 뒤의 잠금으로 둔다
        Timestamp at = Timestamp.from(Instant.now().minus(age));
        jdbc.update("UPDATE user_browser SET human_held_at = ?, started_at = ? WHERE id = ?", at, at, id);
    }


    private static BrowserProperties properties() {
        return new BrowserProperties(
            true,
            "https://browser-proxy.example.test",
            "example/browser:test",
            "example-browser",
            9999,
            "build/unused",
            "/example/browser-profiles",
            "/example/profile",
            512,
            1.0,
            256,
            128,
            5,
            Duration.ofMinutes(10),
            Duration.ofMillis(100),
            Duration.ofMinutes(30),
            Duration.ofMinutes(30),
            null,
            null);
    }


    private static BrowserProfileStore profiles() {
        return new BrowserProfileStore() {
            @Override
            public void ensure(String profileKey) {
                // 검사는 디렉터리를 만들지 않는다
            }


            @Override
            public void delete(String profileKey) {
                // 검사는 디렉터리를 만들지 않는다
            }
        };
    }
}
