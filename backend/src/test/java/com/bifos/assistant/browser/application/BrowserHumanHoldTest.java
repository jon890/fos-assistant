package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.browser.application.port.BrowserUsageHandle;
import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.FakeBrowserRuntime;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 사람 잠금의 회수, 돌려주기, 끄기와 엔티티 저장, 열린 중계 닫기를 실제 DB 로 본다. 뜻은 설계의 「PR3 사람 잠금」 이다. */
@BackendIntegrationTest
class BrowserHumanHoldTest {

    private static final Long USER = 201L;

    @Autowired
    UserBrowserRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private final Set<Long> approvedRuns = new HashSet<>();
    private final BrowserAgentTabs agentTabs = new BrowserAgentTabs(new FakeCdp(), Clock.systemUTC());
    private BrowserHumanHold holds;
    private UserBrowserService service;


    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_browser");
        approvedRuns.clear();
        holds = new BrowserHumanHold(repository, approvedRuns::contains, agentTabs, Clock.systemUTC(), transactions);
        service = UserBrowserServiceTestFactory.create(
            repository,
            new FakeBrowserRuntime(),
            profiles(),
            address -> true,
            LiveProperties.fixed(BrowserProperties.class, properties()),
            Clock.systemUTC(),
            new BrowserScreens(
                new FakeCdp(), new FakeCdp(), new BrowserUsage(), agentTabs, holds, () -> Duration.ofMinutes(30), Duration.ofSeconds(2)),
            holds);
    }


    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM user_browser");
    }


    @Test
    @DisplayName("켜진 브라우저만 넘겨받고 두 번 눌러도 성공한다")
    void takesOverRunningBrowserOnce() {
        service.create(USER);
        assertThatThrownBy(() -> holds.takeOver(USER, null))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_BUSY));
        assertThat(holds.held(USER)).isFalse();

        service.start(USER);
        holds.takeOver(USER, "conv-1");
        holds.takeOver(USER, "conv-1");

        assertThat(holds.held(USER)).isTrue();
        assertThat(holds.heldFrom(USER)).contains("conv-1");
    }


    @Test
    @DisplayName("브라우저가 없으면 BROWSER_NOT_FOUND 다")
    void refusesWithoutBrowser() {
        assertThatThrownBy(() -> holds.takeOver(USER, null))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_NOT_FOUND));
    }


    @Test
    @DisplayName("브라우저를 쓰는 승인한 실행이 돌면 넘겨받지 못하고 잠그지 않는다")
    void refusesWhileApprovedRunIsRunning() {
        running();
        approvedRuns.add(USER);

        assertThatThrownBy(() -> holds.takeOver(USER, null))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_APPROVAL_RUNNING));
        assertThat(holds.held(USER)).isFalse();
    }


    @Test
    @DisplayName("돌려주기와 끄기가 잠금을 비운다")
    void giveBackAndStopRelease() {
        running();
        holds.takeOver(USER, "conv-1");
        holds.giveBack(USER);
        assertThat(holds.held(USER)).isFalse();
        assertThat(holds.heldFrom(USER)).isEmpty();
        holds.giveBack(USER);

        holds.takeOver(USER, null);
        service.stop(USER);
        assertThat(repository.findByUserId(USER).orElseThrow().humanHeldAt()).isNull();
    }


    @Test
    @DisplayName("넘겨받기 전에 읽은 엔티티를 저장해도 잠금이 되돌아가지 않는다")
    void staleEntitySaveKeepsHold() {
        running();
        UserBrowser stale = repository.findByUserId(USER).orElseThrow();
        holds.takeOver(USER, null);

        stale.touch(stale.lastActiveAt().plus(Duration.ofMinutes(5)));
        repository.saveAndFlush(stale);

        assertThat(holds.held(USER)).isTrue();
    }


    @Test
    @DisplayName("넘겨받으면 등록한 중계 연결을 닫고, 닫은 핸들은 다시 불리지 않는다")
    void takeoverClosesWatchedRelays() {
        Long id = running();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        holds.watch(id, first::incrementAndGet);
        BrowserUsageHandle gone = holds.watch(id, second::incrementAndGet);
        gone.close();
        agentTabs.created(id, "T1");

        holds.takeOver(USER, null);

        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isZero();
        assertThat(agentTabs.held(id, "T1")).isFalse();
    }


    @Test
    @DisplayName("화면의 잠금 상태는 쥔 탭이 있을 때만 승인한 실행을 본다")
    void viewAsksApprovedRunOnlyWithAgentTabs() {
        running();
        approvedRuns.add(USER);

        assertThat(holds.view(USER, false)).containsEntry("human", false).containsEntry("approved", false);
        assertThat(holds.view(USER, true)).containsEntry("approved", true);

        approvedRuns.clear();
        holds.takeOver(USER, null);
        assertThat(holds.view(USER, true)).containsEntry("human", true).containsEntry("approved", false);
    }


    private Long running() {
        Long id = service.create(USER).id();
        service.start(USER);
        return id;
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
