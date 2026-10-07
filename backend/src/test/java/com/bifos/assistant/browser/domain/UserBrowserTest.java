package com.bifos.assistant.browser.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserBrowserTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final String KEY = "a".repeat(64);

    @Test
    @DisplayName("새 브라우저는 컨테이너 없이 STOPPED 로 생긴다")
    void createsStoppedBrowser() {
        UserBrowser browser = UserBrowser.create(7L, KEY, NOW);

        assertThat(browser.status()).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(browser.userId()).isEqualTo(7L);
        assertThat(browser.profileKey()).isEqualTo(KEY);
        assertThat(browser.containerId()).isNull();
        assertThat(browser.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("켜기는 STARTING 을 거쳐 RUNNING 이 되고 켠 시각과 활동 시각을 남긴다")
    void startsThroughStartingToRunning() {
        UserBrowser browser = UserBrowser.create(7L, KEY, NOW);

        browser.beginStart(NOW);
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.STARTING);

        Instant ready = NOW.plusSeconds(5);
        browser.markRunning("c1", ready);
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.RUNNING);
        assertThat(browser.containerId()).isEqualTo("c1");
        assertThat(browser.startedAt()).isEqualTo(ready);
        assertThat(browser.lastActiveAt()).isEqualTo(ready);
    }

    @Test
    @DisplayName("켜다 실패하면 FAILED 와 까닭을 남기고 다시 켤 수 있다")
    void failsStartAndAllowsRetry() {
        UserBrowser browser = UserBrowser.create(7L, KEY, NOW);
        browser.beginStart(NOW);

        browser.markFailed("start_timeout", NOW);
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.FAILED);
        assertThat(browser.lastError()).isEqualTo("start_timeout");

        browser.beginStart(NOW);
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.STARTING);
        assertThat(browser.lastError()).isNull();
    }

    @Test
    @DisplayName("끄기는 STOPPING 을 거쳐 STOPPED 가 되고 컨테이너 번호를 비운다")
    void stopsThroughStoppingToStopped() {
        UserBrowser browser = running();

        browser.beginStop(NOW);
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.STOPPING);

        browser.markStopped(NOW);
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(browser.containerId()).isNull();
        assertThat(browser.startedAt()).isNull();
    }

    @Test
    @DisplayName("FAILED 인 브라우저도 끌 수 있고 멈추다 실패하면 FAILED 가 된다")
    void stopsFailedBrowserAndFailsStop() {
        UserBrowser browser = UserBrowser.create(7L, KEY, NOW);
        browser.beginStart(NOW);
        browser.markFailed("start_failed", NOW);

        browser.beginStop(NOW);
        browser.markFailed("stop_failed", NOW);

        assertThat(browser.status()).isEqualTo(UserBrowserStatus.FAILED);
        assertThat(browser.lastError()).isEqualTo("stop_failed");
    }

    @Test
    @DisplayName("켜져 있는 브라우저를 다시 켜면 BROWSER_BUSY 다")
    void rejectsStartWhileRunning() {
        UserBrowser browser = running();

        assertThatThrownBy(() -> browser.beginStart(NOW))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_BUSY));
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.RUNNING);
    }

    @Test
    @DisplayName("멈춰 있는 브라우저를 끄면 BROWSER_BUSY 다")
    void rejectsStopWhileStopped() {
        UserBrowser browser = UserBrowser.create(7L, KEY, NOW);

        assertThatThrownBy(() -> browser.beginStop(NOW))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_BUSY));
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.STOPPED);
    }

    @Test
    @DisplayName("STARTING 이 아닌 줄은 RUNNING 으로 올리지 않는다")
    void rejectsRunningWithoutStarting() {
        UserBrowser browser = UserBrowser.create(7L, KEY, NOW);

        assertThatThrownBy(() -> browser.markRunning("c1", NOW))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_BUSY));
    }

    @Test
    @DisplayName("상태 맞추기는 켜진 줄과 전이 중인 줄을 STOPPED 로 돌리고 멈춘 줄은 거절한다")
    void resetsRunningOrTransitioningToStopped() {
        UserBrowser browser = running();
        browser.resetStopped(NOW);
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(browser.containerId()).isNull();

        browser.beginStart(NOW);
        browser.resetStopped(NOW);
        assertThat(browser.status()).isEqualTo(UserBrowserStatus.STOPPED);

        assertThatThrownBy(() -> browser.resetStopped(NOW))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BROWSER_BUSY));
    }

    @Test
    @DisplayName("활동 시각은 마지막 기록에서 1분이 지나야 다시 쓴다")
    void touchesAtMostOncePerMinute() {
        UserBrowser browser = running();
        Instant runningAt = browser.lastActiveAt();

        assertThat(browser.touch(runningAt.plusSeconds(59))).isFalse();
        assertThat(browser.lastActiveAt()).isEqualTo(runningAt);

        Instant later = runningAt.plus(Duration.ofMinutes(1));
        assertThat(browser.touch(later)).isTrue();
        assertThat(browser.lastActiveAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("활동 시각이 비어 있으면 처음 기록은 바로 쓴다")
    void touchesWhenNeverActive() {
        UserBrowser browser = UserBrowser.create(7L, KEY, NOW);

        assertThat(browser.touch(NOW)).isTrue();
        assertThat(browser.lastActiveAt()).isEqualTo(NOW);
    }

    private static UserBrowser running() {
        UserBrowser browser = UserBrowser.create(7L, KEY, NOW);
        browser.beginStart(NOW);
        browser.markRunning("c1", NOW);
        return browser;
    }
}
