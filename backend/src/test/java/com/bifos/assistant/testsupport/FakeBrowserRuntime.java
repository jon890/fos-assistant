package com.bifos.assistant.testsupport;

import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.RuntimeContainer;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리에서 컨테이너를 흉내 내는 대역 proxy 다. 지운 컨테이너와 실패시킬 동작을 검사가 본다.
 *
 * <p>{@link IntegrationTestDoubles} 가 운영 {@link BrowserRuntime} 자리에 넣는다. 운영 코드는 브라우저 기능이 켜져 있을 때만 이 대역을
 * 부르므로, 기능을 끈 기본 상태의 검사에는 영향이 없다. {@link IntegrationTestIsolation} 이 검사 뒤에 {@link #reset()} 으로 비운다.
 * 서비스를 직접 만드는 검사는 새로 만들어 쓴다.
 */
public final class FakeBrowserRuntime implements BrowserRuntime {

    private final Map<String, RuntimeContainer> containers = new LinkedHashMap<>();
    private final List<String> removed = new ArrayList<>();
    private final Set<String> failingActions = ConcurrentHashMap.newKeySet();
    /** 이 컨테이너를 멈추려 하면 실패한다. */
    private final Set<String> failingStops = ConcurrentHashMap.newKeySet();

    private int sequence;

    @Override
    public synchronized String create(String profileKey) {
        fail("create");
        String id = "c" + (++sequence);
        containers.put(id, new RuntimeContainer(id, profileKey, false));
        return id;
    }

    @Override
    public synchronized void start(String containerId) {
        fail("start");
        RuntimeContainer found = containers.get(containerId);
        containers.put(containerId, new RuntimeContainer(containerId, found.profileKey(), true));
    }

    @Override
    public synchronized void stop(String containerId) {
        fail("stop");
        if (failingStops.contains(containerId)) {
            throw new IllegalStateException("browser proxy stop failed with status 500");
        }
        RuntimeContainer found = containers.get(containerId);
        if (found != null) {
            containers.put(containerId, new RuntimeContainer(containerId, found.profileKey(), false));
        }
    }

    @Override
    public synchronized void remove(String containerId) {
        fail("remove");
        containers.remove(containerId);
        removed.add(containerId);
    }

    @Override
    public synchronized Optional<URI> cdpAddress(String containerId) {
        return containers.containsKey(containerId)
                ? Optional.of(URI.create("http://192.0.2.10:9999"))
                : Optional.empty();
    }

    @Override
    public synchronized List<RuntimeContainer> list() {
        fail("list");
        return List.copyOf(containers.values());
    }

    /** 지금 있는 컨테이너다. 번호 순이다. 검사가 비울 수도 있다. */
    public Map<String, RuntimeContainer> containers() {
        return containers;
    }

    /** 지운 컨테이너 번호다. 지운 순서대로다. */
    public List<String> removed() {
        return removed;
    }

    /** 여기 든 동작({@code create}, {@code start}, {@code stop}, {@code remove}, {@code list})은 실패한다. */
    public Set<String> failingActions() {
        return failingActions;
    }

    /** 여기 든 컨테이너를 멈추려 하면 실패한다. */
    public Set<String> failingStops() {
        return failingStops;
    }

    /** 표에 없는 컨테이너를 심는다. */
    public synchronized void plant(String id, String profileKey, boolean running) {
        containers.put(id, new RuntimeContainer(id, profileKey, running));
    }

    /** 컨테이너와 기록, 실패시킬 동작을 모두 비우고 번호를 처음부터 센다. */
    public synchronized void reset() {
        containers.clear();
        removed.clear();
        failingActions.clear();
        failingStops.clear();
        sequence = 0;
    }

    private void fail(String action) {
        if (failingActions.contains(action)) {
            throw new IllegalStateException("browser proxy " + action + " failed with status 500");
        }
    }
}
