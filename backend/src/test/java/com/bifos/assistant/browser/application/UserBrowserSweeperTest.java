package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.application.model.BrowserUsageHandle;
import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.auth.UserAccessRevoked;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 자동 중지와 상태 맞추기, 꺼진 사용자의 브라우저 멈춤을 실제 DB 와 대역 proxy 로 본다. */
@SpringBootTest
@ActiveProfiles("test")
class UserBrowserSweeperTest {

    @Autowired
    UserBrowserRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private FakeBrowserRuntime runtime;
    private BrowserUsage usage;
    private UserBrowserService service;
    private UserBrowserSweeper sweeper;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_browser");
        runtime = new FakeBrowserRuntime();
        usage = new BrowserUsage();
        service = service(true);
        sweeper = new UserBrowserSweeper(service, repository, runtime, usage, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM user_browser");
    }

    @Test
    @DisplayName("유휴 시간이 지난 켜진 브라우저를 멈추고 최근에 쓴 브라우저는 그대로 둔다")
    void stopsIdleBrowser() {
        Long idle = running(201L);
        running(202L);
        ageActivity(idle, Duration.ofMinutes(11));

        sweeper.sweep();

        assertThat(status(201L)).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(status(202L)).isEqualTo(UserBrowserStatus.RUNNING);
    }

    @Test
    @DisplayName("화면이나 중계가 열린 핸들을 쥐고 있으면 유휴 시간이 지나도 멈추지 않고 닫으면 멈춘다")
    void keepsBrowserWithOpenHandle() {
        Long id = running(201L);
        ageActivity(id, Duration.ofMinutes(11));
        BrowserUsageHandle handle = usage.open(id);

        sweeper.sweep();
        assertThat(status(201L)).isEqualTo(UserBrowserStatus.RUNNING);

        handle.close();
        handle.close();
        sweeper.sweep();
        assertThat(status(201L)).isEqualTo(UserBrowserStatus.STOPPED);
    }

    @Test
    @DisplayName("RUNNING 인데 컨테이너가 사라졌으면 STOPPED 로 맞춘다")
    void resetsRunningWithoutContainer() {
        Long id = running(201L);
        runtime.containers.clear();
        ageUpdate(id, Duration.ofSeconds(5));

        sweeper.sweep();

        assertThat(status(201L)).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(repository.findByUserId(201L).orElseThrow().containerId()).isNull();
    }

    @Test
    @DisplayName("2분 넘게 STARTING 으로 남은 줄은 컨테이너를 지우고 STOPPED 로 맞춘다")
    void resetsStuckStarting() {
        Long id = service.create(201L).id();
        UserBrowser browser = repository.findById(id).orElseThrow();
        browser.beginStart(Instant.now());
        repository.saveAndFlush(browser);
        runtime.plant("left", UserBrowserService.profileKey(201L), false);
        ageUpdate(id, Duration.ofMinutes(3));

        sweeper.sweep();

        assertThat(status(201L)).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(runtime.removed).contains("left");
    }

    @Test
    @DisplayName("막 STARTING 이 된 줄의 컨테이너는 지우지 않는다")
    void keepsFreshStartingContainer() {
        Long id = service.create(201L).id();
        UserBrowser browser = repository.findById(id).orElseThrow();
        browser.beginStart(Instant.now());
        repository.saveAndFlush(browser);
        runtime.plant("booting", UserBrowserService.profileKey(201L), false);

        sweeper.sweep();

        assertThat(status(201L)).isEqualTo(UserBrowserStatus.STARTING);
        assertThat(runtime.removed).doesNotContain("booting");
    }

    @Test
    @DisplayName("표에 없는 키와 멈춘 줄의 키, 키가 없는 컨테이너를 지운다")
    void removesOrphanContainers() {
        service.create(201L);
        runtime.plant("stranger", "f".repeat(64), true);
        runtime.plant("stale", UserBrowserService.profileKey(201L), false);
        runtime.plant("unlabeled", null, true);

        sweeper.sweep();

        assertThat(runtime.removed).containsExactlyInAnyOrder("stranger", "stale", "unlabeled");
    }

    @Test
    @DisplayName("한 줄을 멈추다 실패해도 다음 줄은 멈춘다")
    void continuesAfterOneFailure() {
        Long first = running(201L);
        Long second = running(202L);
        ageActivity(first, Duration.ofMinutes(11));
        ageActivity(second, Duration.ofMinutes(11));
        runtime.failingStops.add(repository.findById(first).orElseThrow().containerId());

        sweeper.sweep();

        assertThat(status(201L)).isEqualTo(UserBrowserStatus.FAILED);
        assertThat(status(202L)).isEqualTo(UserBrowserStatus.STOPPED);
    }

    @Test
    @DisplayName("사용자를 끄면 그 사용자의 브라우저를 멈추고 프로필과 줄은 남긴다")
    void stopsBrowserOfRevokedUser() {
        running(201L);

        sweeper.onAccessRevoked(new UserAccessRevoked(201L));

        assertThat(status(201L)).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(runtime.containers).isEmpty();
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 아무것도 하지 않는다")
    void doesNothingWhenDisabled() {
        runtime.plant("stranger", "f".repeat(64), true);
        UserBrowserSweeper disabled =
                new UserBrowserSweeper(service(false), repository, runtime, usage, Clock.systemUTC());

        disabled.sweep();
        disabled.onAccessRevoked(new UserAccessRevoked(201L));

        assertThat(runtime.removed).isEmpty();
    }

    private Long running(Long userId) {
        Long id = service.create(userId).id();
        service.start(userId);
        return id;
    }

    private UserBrowserStatus status(Long userId) {
        return repository.findByUserId(userId).orElseThrow().status();
    }

    private void ageActivity(Long id, Duration age) {
        jdbc.update(
                "UPDATE user_browser SET last_active_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(age)),
                id);
    }

    private void ageUpdate(Long id, Duration age) {
        jdbc.update(
                "UPDATE user_browser SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(age)),
                id);
    }

    private UserBrowserService service(boolean enabled) {
        return new UserBrowserService(
                repository, runtime, profiles(), address -> true, properties(enabled), Clock.systemUTC());
    }

    private static BrowserProperties properties(boolean enabled) {
        return new BrowserProperties(
                enabled,
                "https://browser-proxy.example.test",
                "example/browser:test",
                "example-browser",
                9999,
                "build/unused",
                "/example/browser-profiles",
                512,
                1.0,
                256,
                128,
                5,
                Duration.ofMinutes(10),
                Duration.ofMillis(100));
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
