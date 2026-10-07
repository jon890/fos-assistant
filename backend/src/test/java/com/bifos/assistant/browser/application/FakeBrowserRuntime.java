package com.bifos.assistant.browser.application;

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

/** 메모리에서 컨테이너를 흉내 내는 대역 proxy 다. 지운 컨테이너와 실패시킬 동작을 검사가 본다. */
final class FakeBrowserRuntime implements BrowserRuntime {

    final Map<String, RuntimeContainer> containers = new LinkedHashMap<>();
    final List<String> removed = new ArrayList<>();
    final Set<String> failingActions = ConcurrentHashMap.newKeySet();
    /** 이 컨테이너를 멈추려 하면 실패한다. */
    final Set<String> failingStops = ConcurrentHashMap.newKeySet();

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

    /** 표에 없는 컨테이너를 심는다. */
    synchronized void plant(String id, String profileKey, boolean running) {
        containers.put(id, new RuntimeContainer(id, profileKey, running));
    }

    private void fail(String action) {
        if (failingActions.contains(action)) {
            throw new IllegalStateException("browser proxy " + action + " failed with status 500");
        }
    }
}
