package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.browser.application.model.BrowserScreenInput;
import com.bifos.assistant.browser.application.model.BrowserScreenInput.Kind;
import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.FakeBrowserRuntime;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 브라우저마다 화면 하나, 끄기와 화면 닫기, 자동 중지와 화면의 관계를 실제 DB 와 대역 proxy, 대역 CDP 로 본다. */
@BackendIntegrationTest
class BrowserScreensTest {

    private static final URI CDP = URI.create("http://192.0.2.10:9999");

    @Autowired
    UserBrowserRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private FakeCdp cdp;
    private BrowserUsage usage;
    private BrowserScreens screens;
    private UserBrowserService service;
    private UserBrowserSweeper sweeper;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_browser");
        cdp = new FakeCdp();
        cdp.pages.add(new CdpTarget("T1", "첫 탭", "https://example.com/"));
        usage = new BrowserUsage();
        screens = new BrowserScreens(cdp, cdp, usage, Duration.ofMinutes(30), Duration.ofHours(1));
        FakeBrowserRuntime runtime = new FakeBrowserRuntime();
        service = new UserBrowserService(
                repository,
                runtime,
                profiles(),
                address -> true,
                LiveProperties.fixed(BrowserProperties.class, properties()),
                Clock.systemUTC(),
                screens);
        sweeper = new UserBrowserSweeper(service, repository, runtime, usage, userId -> true, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        screens.close();
        jdbc.update("DELETE FROM user_browser");
    }

    @Test
    @DisplayName("같은 브라우저에 두 번째 화면을 열면 첫 화면에 replaced 를 보내고 닫는다")
    void replacesPreviousScreen() {
        RecordingScreenSink first = new RecordingScreenSink();
        RecordingScreenSink second = new RecordingScreenSink();

        screens.open(1L, 201L, CDP, null, first);
        screens.open(1L, 201L, CDP, null, second);

        assertThat(first.completed).isTrue();
        assertThat(first.data("closed")).containsExactly(Map.of("reason", "replaced"));
        assertThat(second.completed).isFalse();
        assertThat(screens.isOpen(1L)).isTrue();
        assertThat(usage.inUse(1L)).isTrue();
    }

    @Test
    @DisplayName("열린 화면이 없거나 남의 화면만 있으면 입력은 BROWSER_SCREEN_CLOSED 다")
    void rejectsInputWithoutOwnScreen() {
        screens.open(1L, 201L, CDP, null, new RecordingScreenSink());
        BrowserScreenInput reload = new BrowserScreenInput(Kind.RELOAD, null, 0, 0, 0, null, null, null, null, 0, 0);

        assertThatThrownBy(() -> screens.input(202L, reload))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_SCREEN_CLOSED));
        assertThat(cdp.sent("Page.reload")).isEmpty();

        screens.input(201L, reload);
        assertThat(cdp.sent("Page.reload")).hasSize(1);
    }

    @Test
    @DisplayName("탭에 붙지 못하면 BROWSER_START_FAILED 이고 사용 핸들을 남기지 않는다")
    void failsWhenAttachFails() {
        cdp.failConnect = true;

        assertThatThrownBy(() -> screens.open(1L, 201L, CDP, null, new RecordingScreenSink()))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_START_FAILED));
        assertThat(screens.isOpen(1L)).isFalse();
        assertThat(usage.inUse(1L)).isFalse();
    }

    @Test
    @DisplayName("화면을 열면 브라우저를 켜고, 끄면 화면에 stopped 를 보내고 닫는다")
    void stopClosesScreen() {
        Long id = service.create(201L).id();
        RecordingScreenSink sink = new RecordingScreenSink();

        service.openScreen(201L, "https://example.com/login", sink);
        assertThat(status(201L)).isEqualTo(UserBrowserStatus.RUNNING);
        assertThat(screens.isOpen(id)).isTrue();

        service.stop(201L);

        assertThat(sink.completed).isTrue();
        assertThat(sink.data("closed")).containsExactly(Map.of("reason", "stopped"));
        assertThat(screens.isOpen(id)).isFalse();
        assertThat(usage.inUse(id)).isFalse();
    }

    @Test
    @DisplayName("화면이 열려 있으면 유휴 시간이 지나도 자동 중지하지 않고 화면이 닫히면 멈춘다")
    void keepsBrowserWhileScreenIsOpen() {
        Long id = service.create(201L).id();
        RecordingScreenSink sink = new RecordingScreenSink();
        service.openScreen(201L, null, sink);
        ageActivity(id, Duration.ofMinutes(11));

        sweeper.sweep();
        assertThat(status(201L)).isEqualTo(UserBrowserStatus.RUNNING);

        sink.disconnect();
        sweeper.sweep();
        assertThat(status(201L)).isEqualTo(UserBrowserStatus.STOPPED);
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
                Duration.ofMinutes(30));
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
