package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.model.BrowserScreenInput;
import com.bifos.assistant.browser.application.model.BrowserScreenSink;
import com.bifos.assistant.browser.application.model.UserBrowserSnapshot;
import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.CdpProbe;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 사용자 브라우저를 만들고 켜고 끄고 지운다. 전이 규칙은 {@link UserBrowser} 가 갖고 이 서비스는 순서와 proxy 호출을 맡는다.
 *
 * <p>상태 저장은 저장소의 짧은 트랜잭션 하나씩이다. proxy 호출과 CDP 기다리기는 트랜잭션 밖에서 한다. 같은 줄을 다른 요청이 먼저 바꿨으면 낙관적
 * 잠금이 걸려 {@code BROWSER_BUSY} 다.
 *
 * <p>동시 수는 {@code STARTING} 과 {@code RUNNING} 을 센다. Control Plane 은 한 프로세스이므로 세기와 {@code STARTING} 저장을 JVM
 * 잠금 하나로 묶는다. 잠금 안에서 컨테이너를 기다리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBrowserService {

    /** 켜다가 proxy 호출이 실패했다. */
    public static final String START_FAILED = "start_failed";
    /** 켰지만 CDP 가 정한 시간 안에 답하지 않았다. */
    public static final String START_TIMEOUT = "start_timeout";
    /** 멈추거나 지우다 proxy 호출이 실패했다. */
    public static final String STOP_FAILED = "stop_failed";

    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final List<UserBrowserStatus> COUNTED =
            List.of(UserBrowserStatus.STARTING, UserBrowserStatus.RUNNING);

    private final UserBrowserRepository browsers;
    private final BrowserRuntime runtime;
    private final BrowserProfileStore profiles;
    private final CdpProbe cdp;
    private final BrowserProperties properties;
    private final Clock clock;
    private final BrowserScreens screens;
    private final ReentrantLock startLock = new ReentrantLock();

    /** 프로필 디렉터리 이름이다. 첨부 디렉터리 키와 같은 계산이지만 루트가 다르다. */
    public static String profileKey(Long userId) {
        return Sha256.hex("u" + userId);
    }

    /** 기능이 켜져 있는가. 꺼져 있으면 상태 조회 말고 모든 쓰기가 {@code BROWSER_DISABLED} 다. */
    public boolean enabled() {
        return properties.enabled();
    }

    /** 자동 중지까지의 유휴 시간이다. 화면이 안내 문구에 쓴다. */
    public Duration idleTimeout() {
        return properties.idleTimeout();
    }

    /** 관리자 목록이다. 모든 브라우저를 번호 순으로 돌려준다. */
    public List<UserBrowserSnapshot> list() {
        return browsers.findAllByOrderByIdAsc().stream()
                .map(UserBrowserSnapshot::of)
                .toList();
    }

    public Optional<UserBrowserSnapshot> get(Long userId) {
        return browsers.findByUserId(userId).map(UserBrowserSnapshot::of);
    }

    /**
     * 브라우저를 만든다. 남은 프로필 디렉터리를 비우고 새로 만든 뒤 {@code STOPPED} 로 저장한다.
     *
     * @throws ApiException 이미 있으면 {@code BROWSER_EXISTS}
     */
    public UserBrowserSnapshot create(Long userId) {
        requireEnabled();
        if (browsers.findByUserId(userId).isPresent()) {
            throw new ApiException(ErrorCode.BROWSER_EXISTS, "user browser already exists");
        }
        String key = profileKey(userId);
        // 줄이 없는데 디렉터리가 남아 있으면 지우다 만 프로필이다. 남은 쿠키로 로그인된 채 켜지지 않게 비운다
        profiles.delete(key);
        profiles.ensure(key);
        try {
            return UserBrowserSnapshot.of(browsers.saveAndFlush(UserBrowser.create(userId, key, clock.instant())));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.BROWSER_EXISTS, "user browser already exists", ex);
        }
    }

    /**
     * 브라우저를 켠다. 이미 켜져 있으면 그대로 돌려준다.
     *
     * @throws ApiException 동시 수가 찼으면 {@code BROWSER_CAPACITY}, 켜지 못했으면 {@code BROWSER_START_FAILED}, 실패한 줄에 남은
     *     컨테이너를 지우지 못했으면 {@code BROWSER_STOP_FAILED}
     */
    public UserBrowserSnapshot start(Long userId) {
        requireEnabled();
        clearFailedContainer(owned(userId));
        UserBrowser browser;
        startLock.lock();
        try {
            browser = owned(userId);
            if (browser.status() == UserBrowserStatus.RUNNING) {
                return UserBrowserSnapshot.of(browser);
            }
            // 끄다 실패해 컨테이너가 남은 FAILED 도 돌고 있을 수 있어 함께 센다
            long running = browsers.countByStatusIn(COUNTED)
                    + browsers.countByStatusAndContainerIdIsNotNull(UserBrowserStatus.FAILED);
            if (running >= properties.maxRunning()) {
                throw new ApiException(ErrorCode.BROWSER_CAPACITY, "too many browsers are running");
            }
            browser.beginStart(clock.instant());
            browser = save(browser);
        } finally {
            startLock.unlock();
        }
        return launch(browser);
    }

    /** 브라우저를 끈다. 이미 멈춰 있으면 그대로 돌려준다. */
    public UserBrowserSnapshot stop(Long userId) {
        requireEnabled();
        return UserBrowserSnapshot.of(halt(owned(userId)));
    }

    /** 관리자가 그 번호의 브라우저를 끈다. */
    public UserBrowserSnapshot stopById(Long id) {
        requireEnabled();
        return UserBrowserSnapshot.of(halt(byId(id)));
    }

    /**
     * 자동 중지다. 줄을 다시 읽어 아직 켜져 있고 유휴 기준 시각보다 오래 쓰지 않았을 때만, 읽은 버전으로 멈춘다.
     *
     * <p>후보를 읽은 뒤 활동이 기록됐으면 멈추지 않는다. 다시 읽은 뒤에 기록됐으면 낙관적 잠금이 걸려 {@code BROWSER_BUSY} 다.
     *
     * @return 멈췄으면 참
     */
    public boolean stopIfIdle(Long id, Instant cutoff) {
        requireEnabled();
        Optional<UserBrowser> idle = browsers.findById(id)
                .filter(browser -> browser.status() == UserBrowserStatus.RUNNING)
                .filter(browser ->
                        browser.lastActiveAt() == null || browser.lastActiveAt().isBefore(cutoff));
        idle.ifPresent(this::halt);
        return idle.isPresent();
    }

    /** 끈 뒤 프로필 디렉터리와 줄을 지운다. 로그인이 모두 풀린다. */
    public void delete(Long userId) {
        requireEnabled();
        remove(owned(userId));
    }

    /** 관리자가 그 번호의 브라우저를 지운다. */
    public void deleteById(Long id) {
        requireEnabled();
        remove(byId(id));
    }

    /**
     * 화면 입력이나 중계 통신이 있었다. 켜져 있을 때만 마지막 활동 시각을 1분에 한 번까지 쓴다.
     *
     * <p>다른 전이와 겹쳐 저장하지 못하면 이번 기록은 버린다. 다음 활동이 다시 쓴다.
     */
    public void touch(Long userId) {
        browsers.findByUserId(userId)
                .filter(browser -> browser.status() == UserBrowserStatus.RUNNING)
                .filter(browser -> browser.touch(clock.instant()))
                .ifPresent(browser -> {
                    try {
                        browsers.save(browser);
                    } catch (OptimisticLockingFailureException ex) {
                        log.debug("user browser touch skipped id={}", browser.id());
                    }
                });
    }

    /**
     * 로그인 화면을 연다. 꺼져 있으면 켠다. 이 브라우저에 열려 있던 앞의 화면은 닫힌다.
     *
     * @param url 시작 주소. 없으면 지금 주소에 머문다
     * @throws ApiException 켜기의 오류와 같고, 탭에 붙지 못했으면 {@code BROWSER_START_FAILED}
     */
    public void openScreen(Long userId, String url, BrowserScreenSink sink) {
        UserBrowserSnapshot started = start(userId);
        URI address = Optional.ofNullable(owned(userId).containerId())
                .flatMap(runtime::cdpAddress)
                .orElseThrow(() -> new ApiException(ErrorCode.BROWSER_START_FAILED, "user browser has no cdp address"));
        screens.open(started.id(), userId, address, url, sink);
        touch(userId);
    }

    /**
     * 요청자의 열린 화면에 입력을 보내고 활동을 기록한다.
     *
     * @throws ApiException 열린 화면이 없으면 {@code BROWSER_SCREEN_CLOSED}
     */
    public void screenInput(Long userId, BrowserScreenInput input) {
        requireEnabled();
        screens.input(userId, input);
        touch(userId);
    }

    /** 컨테이너를 만들고 켜고 CDP 를 기다린다. 실패하면 컨테이너를 지우고 {@code FAILED} 로 둔다. */
    private UserBrowserSnapshot launch(UserBrowser browser) {
        String containerId = null;
        String failure = START_FAILED;
        try {
            profiles.ensure(browser.profileKey());
            containerId = runtime.create(browser.profileKey());
            runtime.start(containerId);
            if (awaitReady(containerId)) {
                browser.markRunning(containerId, clock.instant());
                return UserBrowserSnapshot.of(save(browser));
            }
            failure = START_TIMEOUT;
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.BROWSER_BUSY) {
                // 다른 전이가 이 줄을 먼저 바꿨다. 띄운 컨테이너만 거두고 그 전이의 결과를 둔다
                removeQuietly(containerId);
                throw ex;
            }
            log.warn("user browser start failed id={} code={}", browser.id(), ex.code());
        } catch (RuntimeException ex) {
            log.warn(
                    "user browser start failed id={} error={}",
                    browser.id(),
                    ex.getClass().getSimpleName());
        }
        removeQuietly(containerId);
        browser.markFailed(failure, clock.instant());
        save(browser);
        throw new ApiException(ErrorCode.BROWSER_START_FAILED, "user browser did not start: " + failure);
    }

    private boolean awaitReady(String containerId) {
        Instant deadline = clock.instant().plus(properties.startTimeout());
        while (true) {
            Optional<URI> address = runtime.cdpAddress(containerId);
            if (address.isPresent() && cdp.ready(address.get())) {
                return true;
            }
            Duration left = Duration.between(clock.instant(), deadline);
            if (left.isNegative() || left.isZero()) {
                return false;
            }
            sleep(left.compareTo(POLL_INTERVAL) < 0 ? left : POLL_INTERVAL);
        }
    }

    private UserBrowser halt(UserBrowser browser) {
        if (browser.status() == UserBrowserStatus.STOPPED) {
            return browser;
        }
        // 화면이 먼저 닫혀야 멈춘 브라우저에 붙은 화면이 남지 않는다
        screens.close(browser.id());
        browser.beginStop(clock.instant());
        UserBrowser stopping = save(browser);
        try {
            if (stopping.containerId() != null) {
                runtime.stop(stopping.containerId());
                runtime.remove(stopping.containerId());
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "user browser stop failed id={} error={}",
                    stopping.id(),
                    ex.getClass().getSimpleName());
            stopping.markFailed(STOP_FAILED, clock.instant());
            save(stopping);
            throw new ApiException(ErrorCode.BROWSER_STOP_FAILED, "user browser did not stop", ex);
        }
        stopping.markStopped(clock.instant());
        return save(stopping);
    }

    /** 끈 줄을 그 버전으로 지운 뒤에 프로필을 지운다. 그 사이 다른 전이가 줄을 바꿨으면 줄과 프로필을 모두 남긴다. */
    private void remove(UserBrowser browser) {
        UserBrowser stopped = halt(browser);
        try {
            browsers.delete(stopped);
        } catch (OptimisticLockingFailureException ex) {
            throw new ApiException(ErrorCode.BROWSER_BUSY, "user browser changed concurrently", ex);
        }
        profiles.delete(stopped.profileKey());
    }

    /**
     * 끄다 실패한 줄에 컨테이너가 남아 있으면 켜기 전에 지운다. {@code beginStart} 가 번호를 지우면 그 컨테이너를 다시 찾지 못한다.
     *
     * <p>proxy 가 그 컨테이너를 모르면 지운 것으로 본다. 지우지 못하면 번호를 남긴 채 거절한다.
     */
    private void clearFailedContainer(UserBrowser browser) {
        if (browser.status() != UserBrowserStatus.FAILED || browser.containerId() == null) {
            return;
        }
        try {
            runtime.remove(browser.containerId());
        } catch (RuntimeException ex) {
            log.warn(
                    "user browser leftover removal failed id={} error={}",
                    browser.id(),
                    ex.getClass().getSimpleName());
            throw new ApiException(ErrorCode.BROWSER_STOP_FAILED, "user browser leftover container not removed", ex);
        }
        // 번호를 비워야 동시 수를 셀 때 이미 지운 자기 컨테이너가 들지 않는다
        browser.clearLeftoverContainer(clock.instant());
        save(browser);
    }

    private void removeQuietly(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            runtime.remove(containerId);
        } catch (RuntimeException ex) {
            // 남은 컨테이너는 상태 맞추기가 라벨로 찾아 지운다
            log.warn(
                    "user browser container cleanup failed error={}",
                    ex.getClass().getSimpleName());
        }
    }

    private UserBrowser save(UserBrowser browser) {
        try {
            return browsers.saveAndFlush(browser);
        } catch (OptimisticLockingFailureException ex) {
            throw new ApiException(ErrorCode.BROWSER_BUSY, "user browser changed concurrently", ex);
        }
    }

    private UserBrowser owned(Long userId) {
        return browsers.findByUserId(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.BROWSER_NOT_FOUND, "no user browser"));
    }

    private UserBrowser byId(Long id) {
        return browsers.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.BROWSER_NOT_FOUND, "no user browser"));
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(ErrorCode.BROWSER_DISABLED, "user browser is disabled");
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the browser", ex);
        }
    }
}
