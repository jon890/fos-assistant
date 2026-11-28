package com.bifos.assistant.hermes.infra;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 사건 스트림 하나에서 도구의 걸린 시간을 정한다. Hermes 가 준 값이 있으면 그대로 쓴다.
 *
 * <p>v0.23.2 의 실행 대기열 경로는 완료 사건의 {@code duration} 을 늘 0 으로 보낸다(0 은 모름으로 읽는다). 그때는 같은
 * {@code tool_call_id} 의 시작과 완료 사건 {@code timestamp} 차이로 계산한다. 화면에 흘리는 사건과 저장하는 사건이 같은 값을 받는다
 * (스펙 run-trace REQ1). 짝이 없거나 시각이 거꾸로면 모름이다. 끝나기 전에 같은 번호가 다시 시작되면 어느 시작과 짝인지 몰라 그 번호는
 * 계산하지 않는다(llama.cpp 는 같은 번호를 다시 쓴다). 스트림마다 하나씩 두고 다른 스트림과 나누지 않는다.
 */
final class ToolElapsed {
    private final Map<String, Double> startedAt = new LinkedHashMap<>();
    private final Set<String> ambiguous = new HashSet<>();


    /**
     * @param timestamp 사건의 {@code timestamp}(초). 없으면 null
     * @param reported Hermes 가 준 걸린 시간(밀리초). 모르면 null
     */
    Long of(String type, String toolCallId, Double timestamp, Long reported) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return reported;
        }
        if ("tool.started".equalsIgnoreCase(type)) {
            if (startedAt.containsKey(toolCallId) || ambiguous.contains(toolCallId)) {
                startedAt.remove(toolCallId);
                ambiguous.add(toolCallId);
            } else if (timestamp != null) {
                startedAt.put(toolCallId, timestamp);
            }
            return reported;
        }
        if (!"tool.completed".equalsIgnoreCase(type) || ambiguous.contains(toolCallId)) {
            return reported;
        }
        Double started = startedAt.remove(toolCallId);
        if (reported != null || started == null || timestamp == null) {
            return reported;
        }
        long elapsed = Math.round((timestamp - started) * 1000);
        return elapsed < 0 ? null : elapsed;
    }
}
