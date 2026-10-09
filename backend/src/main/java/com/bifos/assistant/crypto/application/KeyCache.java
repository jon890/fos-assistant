package com.bifos.assistant.crypto.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * 수명과 개수에 상한이 있는 작은 캐시다. 푼 데이터 key 를 담는다.
 *
 * <p>수명이 지난 값은 꺼낼 때 버린다. 개수를 넘으면 가장 오래 쓰지 않은 값부터 버린다. 메서드마다 이 객체를 잠근다.
 */
final class KeyCache<K, V> {

    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);

    KeyCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    synchronized Optional<V> get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    synchronized void put(K key, V value) {
        entries.put(key, new Entry<>(value, clock.instant().plus(ttl)));
        while (entries.size() > maxEntries) {
            K eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    /** 값과 버릴 시각이다. */
    private record Entry<V>(V value, Instant expiresAt) {}
}
