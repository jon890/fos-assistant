package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.port.BrowserUsageHandle;
import com.bifos.assistant.browser.domain.CdpConnector;
import com.bifos.assistant.browser.domain.CdpTarget;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 에이전트가 쥔 탭을 브라우저마다 JVM 메모리에 둔다. 판정은 {@code docs/features/browser-cowork/design.md} 의 「PR2 에이전트가 쥔 탭」
 * 이다.
 *
 * <p>중계로 열린 탭 연결, 중계로 만든 지 얼마 안 된 탭, 쥔 탭이 연 탭이 쥔 탭이다. {@link BrowserUsage} 처럼 메모리에 둔다. Control
 * Plane 이 다시 뜨면 중계 연결도 함께 끊긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserAgentTabs {

    /** 중계로 만들고 아직 연결이 붙지 않은 탭을 쥐는 시간이다. 플러그인은 만든 직후 붙는다. */
    static final Duration CREATED_HOLD = Duration.ofSeconds(30);
    /** 연 탭을 따라 올라가는 단계의 상한이다. 잘못된 고리에서 멈추게 한다. */
    private static final int MAX_OPENER_DEPTH = 5;

    private final CdpConnector connector;
    private final Clock clock;
    private final Map<Long, Tabs> browsers = new ConcurrentHashMap<>();


    /** 중계 연결이 그 탭에 붙었다. 돌려받은 핸들을 닫으면 그 연결 몫을 놓는다. 두 번 닫아도 한 번만 놓는다. */
    public BrowserUsageHandle hold(Long browserId, String targetId) {
        Tabs tabs = browsers.computeIfAbsent(browserId, id -> new Tabs());
        synchronized (tabs) {
            tabs.connections.merge(targetId, 1, Integer::sum);
            tabs.created.remove(targetId);
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                release(browserId, targetId);
            }
        };
    }


    /** 중계가 그 탭을 만들었다. 연결이 붙기 전에도 {@link #CREATED_HOLD} 동안 쥔다. */
    public void created(Long browserId, String targetId) {
        Tabs tabs = browsers.computeIfAbsent(browserId, id -> new Tabs());
        synchronized (tabs) {
            if (!tabs.connections.containsKey(targetId)) {
                tabs.created.put(targetId, clock.instant());
            }
        }
    }


    /** 그 탭을 에이전트가 쥐었는가. 연 탭은 {@link #agentTabs} 가 읽어 둔 것만 본다. */
    public boolean held(Long browserId, String targetId) {
        Tabs tabs = browsers.get(browserId);
        if (tabs == null) {
            return false;
        }
        synchronized (tabs) {
            return tabs.held(targetId, clock.instant());
        }
    }


    /**
     * 지금 탭들 가운데 에이전트가 쥔 탭이다. 처음 보는 탭이 있고 쥔 탭이 하나라도 있으면 연 탭을 한 번 읽는다. 읽지 못하면 그 탭들의 연
     * 탭을 모르는 것으로 둔다.
     */
    public Set<String> agentTabs(Long browserId, URI cdp, List<CdpTarget> pages) {
        Tabs tabs = browsers.get(browserId);
        if (tabs == null) {
            return Set.of();
        }
        Set<String> ids = pages.stream().map(CdpTarget::id).collect(Collectors.toSet());
        boolean lookup;
        synchronized (tabs) {
            tabs.seen.retainAll(ids);
            tabs.openers.keySet().retainAll(ids);
            lookup = !tabs.seen.containsAll(ids) && tabs.anyHeld(clock.instant());
            if (!lookup) {
                tabs.seen.addAll(ids);
            }
        }
        if (lookup) {
            learnOpeners(browserId, tabs, cdp, ids);
        }
        synchronized (tabs) {
            Instant now = clock.instant();
            return ids.stream().filter(id -> tabs.held(id, now)).collect(Collectors.toSet());
        }
    }


    private void learnOpeners(Long browserId, Tabs tabs, URI cdp, Set<String> ids) {
        Map<String, String> openers;
        try {
            openers = connector.openers(cdp);
        } catch (RuntimeException ex) {
            log.debug("browser opener lookup failed id={} error={}", browserId, ex.getClass().getSimpleName());
            return;
        }
        synchronized (tabs) {
            openers.forEach((id, opener) -> {
                if (ids.contains(id)) {
                    tabs.openers.put(id, opener);
                }
            });
            tabs.seen.addAll(ids);
        }
    }


    private void release(Long browserId, String targetId) {
        browsers.computeIfPresent(browserId, (id, tabs) -> {
            synchronized (tabs) {
                tabs.connections.computeIfPresent(targetId, (tab, count) -> count > 1 ? count - 1 : null);
                tabs.created.values().removeIf(at -> at.plus(CREATED_HOLD).isBefore(clock.instant()));
                return tabs.connections.isEmpty() && tabs.created.isEmpty() ? null : tabs;
            }
        });
    }


    /** 브라우저 하나의 상태다. 읽고 쓸 때 이 객체로 잠근다. */
    private static final class Tabs {

        private final Map<String, Integer> connections = new HashMap<>();
        private final Map<String, Instant> created = new HashMap<>();
        /** 탭에서 그 탭을 연 탭으로 가는 표다. 읽어 둔 것만 담는다. */
        private final Map<String, String> openers = new HashMap<>();
        /** 연 탭을 읽었거나 읽을 필요가 없었던 탭이다. */
        private final Set<String> seen = new HashSet<>();


        boolean held(String targetId, Instant now) {
            String current = targetId;
            for (int depth = 0; current != null && depth <= MAX_OPENER_DEPTH; depth++) {
                if (direct(current, now)) {
                    return true;
                }
                current = openers.get(current);
            }
            return false;
        }


        boolean anyHeld(Instant now) {
            return !connections.isEmpty() || created.keySet().stream().anyMatch(id -> direct(id, now));
        }


        private boolean direct(String targetId, Instant now) {
            if (connections.containsKey(targetId)) {
                return true;
            }
            Instant at = created.get(targetId);
            return at != null && !now.isAfter(at.plus(CREATED_HOLD));
        }
    }
}
