package com.bifos.assistant.testsupport;

import com.bifos.assistant.chat.delivery.application.port.AutoTurnResultSource;
import com.bifos.assistant.chat.delivery.application.dto.AutoTurnResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 다른 turn 이 먼저 전했다고 적은 것처럼 전달 표시가 세지 않는 열쇠들이다. */
    private final Set<String> deliveredElsewhere = ConcurrentHashMap.newKeySet();


    /** 그 대화에 전할 결과를 하나 넣는다. */
    public void offer(Long conversationId, AutoTurnResult result) {
        pending.computeIfAbsent(conversationId, id -> new CopyOnWriteArrayList<>())
            .add(result);
        offered.computeIfAbsent(conversationId, id -> new CopyOnWriteArrayList<>())
            .add(result);
    }


    /**
     * 아직 전하지 않은 결과로 내지만 전달 표시는 바꾼 줄이 없다고 답하는 결과를 넣는다. 결과를 읽은 뒤 다른 turn 이 먼저 전한
     * 경우를 흉내 낸다.
     */
    public void offerDeliveredElsewhere(Long conversationId, AutoTurnResult result) {
        offer(conversationId, result);
        deliveredElsewhere.add(result.key());
    }


    /** 넣은 결과를 모두 비운다. */
    public void reset() {
        pending.clear();
        offered.clear();
        deliveredElsewhere.clear();
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
    public int markDelivered(List<String> keys, Instant now) {
        int marked = 0;
        for (List<AutoTurnResult> results : pending.values()) {
            int before = results.size();
            results.removeIf(result -> keys.contains(result.key()) && !deliveredElsewhere.contains(result.key()));
            marked += before - results.size();
        }
        return marked;
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
