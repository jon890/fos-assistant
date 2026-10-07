package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.model.BrowserScreenInput;
import com.bifos.assistant.browser.application.model.BrowserScreenSink;
import com.bifos.assistant.browser.domain.CdpConnector;
import com.bifos.assistant.browser.domain.CdpTargets;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 브라우저마다 열린 로그인 화면 하나를 갖는 JVM 메모리 등록부다. 화면마다 도는 예약은 스케줄러 하나로 돈다. */
@Slf4j
@Component
public class BrowserScreens implements AutoCloseable {

    private static final Duration TAB_INTERVAL = Duration.ofSeconds(2);
    // 끊긴 받는 쪽을 알아보려고 주석 줄을 보내는 간격이다
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);
    private final CdpTargets targets;
    private final CdpConnector connector;
    private final BrowserUsage usage;
    // 화면을 열 때마다 읽는다. 운영 설정을 바꾸면 다음 화면부터 적용된다
    private final Supplier<Duration> timeout;
    private final Duration tabInterval;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<Long, BrowserScreenSession> screens = new ConcurrentHashMap<>();

    @Autowired
    public BrowserScreens(
            CdpTargets targets,
            CdpConnector connector,
            BrowserUsage usage,
            LiveProperties<BrowserProperties> properties) {
        this(targets, connector, usage, () -> properties.current().screenTimeout(), TAB_INTERVAL);
    }

    BrowserScreens(
            CdpTargets targets,
            CdpConnector connector,
            BrowserUsage usage,
            Supplier<Duration> timeout,
            Duration tabInterval) {
        this.targets = targets;
        this.connector = connector;
        this.usage = usage;
        this.timeout = timeout;
        this.tabInterval = tabInterval;
        this.scheduler = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("browser-screen-", 0).factory());
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    /** 지금 탭에 화면을 열고 앞의 화면은 닫는다. 탭에 붙지 못했으면 {@code BROWSER_START_FAILED} 다. */
    public void open(Long browserId, Long userId, URI cdp, String url, BrowserScreenSink sink) {
        BrowserScreenSession session = new BrowserScreenSession(
                browserId, userId, cdp, targets, connector, usage.open(browserId), sink, scheduler, this::forget);
        BrowserScreenSession previous = screens.put(browserId, session);
        if (previous != null) {
            previous.close(BrowserScreenSession.REPLACED);
        }
        try {
            session.start(url, timeout.get(), tabInterval, HEARTBEAT);
        } catch (RuntimeException ex) {
            String error = ex.getClass().getSimpleName();
            log.warn("browser screen could not attach id={} error={}", browserId, error);
            session.close(null);
            throw new ApiException(ErrorCode.BROWSER_START_FAILED, "browser screen could not attach", ex);
        }
    }

    /** 요청자의 열린 화면에만 입력을 보낸다. 열린 화면이 없으면 {@code BROWSER_SCREEN_CLOSED} 다. */
    public void input(Long userId, BrowserScreenInput input) {
        screens.values().stream()
                .filter(session -> session.userId().equals(userId) && !session.closed())
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.BROWSER_SCREEN_CLOSED, "no open browser screen"))
                .input(input);
    }

    /** 브라우저를 멈추기 전에 그 화면을 닫는다. 받는 쪽에 {@code closed}({@code stopped})를 보낸다. */
    public void close(Long browserId) {
        BrowserScreenSession session = screens.get(browserId);
        if (session != null) {
            session.close(BrowserScreenSession.STOPPED);
        }
    }

    /** 그 브라우저에 열린 화면이 있는가. */
    public boolean isOpen(Long browserId) {
        return screens.containsKey(browserId);
    }

    /** 끌 때 모든 화면을 닫고 스케줄러를 멈춘다. */
    @Override
    public void close() {
        List.copyOf(screens.values()).forEach(session -> session.close(BrowserScreenSession.STOPPED));
        scheduler.shutdownNow();
    }

    private void forget(BrowserScreenSession session) {
        screens.remove(session.browserId(), session);
    }
}
