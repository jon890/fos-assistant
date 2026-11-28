package com.bifos.assistant.usage.execution.application;

import com.bifos.assistant.usage.execution.application.dto.ToolTrace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 도구 호출의 사건 줄과 plugin 기록을 한 줄에 모으는 메모리 장부다(docs/features/run-trace/design.md 「짝짓기 장부」).
 *
 * <p>plugin 기록은 Hermes 사건 스트림과 다른 길로 와서 완료 사건보다 먼저 올 수도, 나중에 올 수도 있다. 먼저 오면 기다리는 기록에
 * 두었다가 완료 줄을 만들 때 꺼내고, 나중에 오면 저장된 줄의 번호를 돌려줘 그 줄을 고치게 한다. 모든 메서드가 이 객체의 잠금 안에서
 * 돌아 두 길이 서로를 놓치지 않는다.
 *
 * <p>같은 실행에서 호출 번호가 두 번 쓰이면(llama.cpp 는 모든 호출에 같은 번호를 쓴다) 어느 줄의 기록인지 몰라 그 번호의 기록은
 * 버린다. 다른 줄에 붙는 것보다 버리는 것이 낫다.
 *
 * <p>항목은 {@link #TTL} 뒤에 버리고 표마다 {@link #LIMIT} 개를 넘으면 오래된 것부터 버린다. 기록은 관측용이라 버려도 실행은
 * 그대로다(스펙 run-trace REQ7).
 */
@Component
@RequiredArgsConstructor
public class ToolTraceLedger {

    static final Duration TTL = Duration.ofMinutes(10);
    static final int LIMIT = 5_000;

    private final Clock clock;
    private final Map<Key, Entry<ToolTrace>> waiting = new LinkedHashMap<>();
    private final Map<Key, Entry<Long>> saved = new LinkedHashMap<>();
    private final Map<Key, Entry<Boolean>> ambiguous = new LinkedHashMap<>();


    /** 완료 줄을 만들 때 부른다. 먼저 와서 기다리던 기록을 꺼낸다. 없으면 null 이다. */
    public synchronized ToolTrace claim(Long executionId, String toolCallId) {
        expire();
        Key key = new Key(executionId, toolCallId);
        Entry<ToolTrace> entry = waiting.remove(key);
        return entry == null || ambiguous.containsKey(key) ? null : entry.value();
    }


    /** 완료 줄을 저장한 직후 부른다. 만들기와 저장 사이에 온 기록이 있으면 꺼내 돌려준다. 없으면 null 이다. */
    public synchronized ToolTrace saved(Long executionId, String toolCallId, Long eventId) {
        expire();
        Key key = new Key(executionId, toolCallId);
        Entry<ToolTrace> entry = waiting.remove(key);
        Entry<Long> earlier = saved.get(key);
        if (ambiguous.containsKey(key) || earlier != null && !earlier.value().equals(eventId)) {
            markAmbiguous(key);
            return null;
        }
        put(saved, key, eventId);
        return entry == null ? null : entry.value();
    }


    /** plugin 기록이 왔을 때 부른다. 완료 줄이 이미 저장됐으면 그 사건 번호를, 아니면 기록을 맡아 두고 null 을 돌려준다. */
    public synchronized Long offer(Long executionId, String toolCallId, ToolTrace trace) {
        expire();
        Key key = new Key(executionId, toolCallId);
        if (ambiguous.containsKey(key)) {
            return null;
        }
        if (waiting.containsKey(key)) {
            markAmbiguous(key);
            return null;
        }
        Entry<Long> stored = saved.get(key);
        if (stored != null) {
            return stored.value();
        }
        put(waiting, key, trace);
        return null;
    }


    private void markAmbiguous(Key key) {
        waiting.remove(key);
        saved.remove(key);
        put(ambiguous, key, Boolean.TRUE);
    }


    private <T> void put(Map<Key, Entry<T>> table, Key key, T value) {
        table.remove(key);
        table.put(key, new Entry<>(value, clock.instant()));
        Iterator<Key> oldest = table.keySet().iterator();
        while (table.size() > LIMIT && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }


    private void expire() {
        Instant cutoff = clock.instant().minus(TTL);
        expire(waiting, cutoff);
        expire(saved, cutoff);
        expire(ambiguous, cutoff);
    }


    private static <T> void expire(Map<Key, Entry<T>> table, Instant cutoff) {
        Iterator<Entry<T>> entries = table.values().iterator();
        while (entries.hasNext()) {
            if (!entries.next().at().isBefore(cutoff)) {
                return;
            }
            entries.remove();
        }
    }


    private record Key(Long executionId, String toolCallId) {
    }

    private record Entry<T>(T value, Instant at) {
    }
}
