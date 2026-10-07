package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.model.BrowserScreenInput;
import com.bifos.assistant.browser.application.model.BrowserScreenSink;
import com.bifos.assistant.browser.domain.CdpConnector;
import com.bifos.assistant.browser.domain.CdpTargets;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 브라우저마다 열린 로그인 화면 하나를 갖는 등록부다.
 *
 * <p>새 화면을 열면 앞의 화면에 {@code closed}({@code replaced})를 보내고 닫는다. 브라우저를 멈추기 전에 {@link #close(Long)} 로
 * 화면을 닫는다. 화면마다 도는 탭 목록 확인과 시간 초과는 이 등록부의 스케줄러 하나로 돌고, 화면이 닫히면 취소된다.
 *
 * <p>JVM 메모리에 둔다. Control Plane 은 한 프로세스이고, 재기동하면 SSE 도 함께 끊긴다.
 */
@Slf4j
@Component
public class BrowserScreens implements AutoCloseable {

    private static final Duration TAB_INTERVAL = Duration.ofSeconds(2);

    private final CdpTargets targets;
    private final CdpConnector connector;
    private final BrowserUsage usage;
    private final Duration timeout;
    private final Duration tabInterval;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<Long, BrowserScreenSession> screens = new ConcurrentHashMap<>();

    @Autowired
    public BrowserScreens(
            CdpTargets targets, CdpConnector connector, BrowserUsage usage, BrowserProperties properties) {
        this(targets, connector, usage, properties.screenTimeout(), TAB_INTERVAL);
    }

    BrowserScreens(
            CdpTargets targets, CdpConnector connector, BrowserUsage usage, Duration timeout, Duration tabInterval) {
        this.targets = targets;
        this.connector = connector;
        this.usage = usage;
        this.timeout = timeout;
        this.tabInterval = tabInterval;
        this.scheduler = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("browser-screen-", 0).factory());
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    /**
     * 그 브라우저의 지금 탭에 화면을 연다. 앞의 화면이 있으면 닫는다.
     *
     * @param url 시작 주소. 없으면 지금 주소에 머문다
     * @throws ApiException 탭에 붙지 못했으면 {@code BROWSER_START_FAILED}
     */
    public void open(Long browserId, Long userId, URI cdp, String url, BrowserScreenSink sink) {
        BrowserScreenSession session = new BrowserScreenSession(
                browserId, userId, cdp, targets, connector, usage.open(browserId), sink, scheduler, this::forget);
        BrowserScreenSession previous = screens.put(browserId, session);
        if (previous != null) {
            previous.close(BrowserScreenSession.REPLACED);
        }
        try {
            session.start(url, timeout, tabInterval);
        } catch (RuntimeException ex) {
            log.warn(
                    "browser screen could not attach id={} error={}",
                    browserId,
                    ex.getClass().getSimpleName());
            session.close(null);
            throw new ApiException(ErrorCode.BROWSER_START_FAILED, "browser screen could not attach", ex);
        }
    }

    /**
     * 요청자의 열린 화면에 입력을 보낸다. 요청자의 화면만 찾으므로 남의 화면에는 닿지 않는다.
     *
     * @throws ApiException 열린 화면이 없으면 {@code BROWSER_SCREEN_CLOSED}
     */
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
