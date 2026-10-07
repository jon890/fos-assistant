package com.bifos.assistant.testsupport;

import com.bifos.assistant.chat.application.AutoTurnResultSource;
import com.bifos.assistant.chat.application.model.AutoTurnResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 위임 결과 말고 다른 출처의 결과를 내는 대역이다. 검사가 넣은 결과만 낸다. 사용자는 구분하지 않는다.
 *
 * <p>비어 있으면 아무 결과도 내지 않는다. {@link IntegrationTestIsolation} 이 검사마다 {@link #reset()} 으로 비운다.
 */
public class TestAutoTurnResultSource implements AutoTurnResultSource {

    /** 전달 묶음의 항목에 적히는 이 대역의 출처 이름이다. */
    public static final String SOURCE = "TEST_SOURCE";

    private final Map<Long, List<AutoTurnResult>> pending = new ConcurrentHashMap<>();

    /** 전한 뒤에도 다시 읽을 수 있게 넣은 결과를 모두 둔다. */
    private final Map<Long, List<AutoTurnResult>> offered = new ConcurrentHashMap<>();

    /** 그 대화에 전할 결과를 하나 넣는다. */
    public void offer(Long conversationId, AutoTurnResult result) {
        pending.computeIfAbsent(conversationId, id -> new CopyOnWriteArrayList<>())
                .add(result);
        offered.computeIfAbsent(conversationId, id -> new CopyOnWriteArrayList<>())
                .add(result);
    }

    /** 넣은 결과를 모두 비운다. */
    public void reset() {
        pending.clear();
        offered.clear();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<AutoTurnResult> undelivered(Long conversationId) {
        return List.copyOf(pending.getOrDefault(conversationId, List.of()));
    }

    @Override
    public void markDelivered(List<String> keys, Instant now) {
        pending.values().forEach(results -> results.removeIf(result -> keys.contains(result.key())));
    }

    @Override
    public List<AutoTurnResult> resultsFor(Long conversationId, Long userId, List<String> keys) {
        List<AutoTurnResult> results = offered.getOrDefault(conversationId, List.of());
        return keys.stream()
                .flatMap(key -> results.stream()
                        .filter(result -> result.key().equals(key))
                        .limit(1))
                .toList();
    }

    @Override
    public List<Long> conversationsWithUndelivered() {
        return pending.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
    }
}
