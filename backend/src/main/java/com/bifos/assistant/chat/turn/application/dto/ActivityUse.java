package com.bifos.assistant.chat.turn.application.dto;

import com.bifos.assistant.usage.execution.application.dto.ConnectorToolName;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 답이 쓴 도구나 도우미 한 가지다. 작업 과정의 접힌 줄이 종류를 잇는 데 쓴다(docs/features/app-shell/design.md 「작업 과정의 이름」).
 *
 * @param kind {@code TOOL} 이나 {@code SUBAGENT}
 * @param toolName 도구의 등록 이름. 도우미는 null
 * @param connector 플러그인 도구의 서비스 이름과 도구 제목. 플러그인 도구가 아니거나 찾지 못했으면 null
 */
public record ActivityUse(String kind, String toolName, ConnectorToolName connector) {


    public static final String TOOL = "TOOL";
    public static final String SUBAGENT = "SUBAGENT";

    private record Mark(Instant at, long executionId, int sequence, ActivityUse use) {
    }


    /**
     * 실행 트리 하나의 사건에서 쓴 도구와 도우미를 처음 쓴 차례로 고른다. 차례는 사건의 때, 실행 번호, 순번 순이다.
     * 자식 실행은 첫 사건의 때에 도우미 한 줄이다. 플러그인 이름은 붙이지 않는다. 부르는 쪽이 답 전체의 도구 이름을 한 번에 찾는다.
     */
    public static List<ActivityUse> firstUses(
        Long rootId, List<AgentExecution> tree, Map<Long, List<ExecutionEvent>> eventsByExecution) {
        List<Mark> marks = new ArrayList<>();
        for (AgentExecution execution : tree) {
            List<ExecutionEvent> events = eventsByExecution.getOrDefault(execution.id(), List.of());
            if (!rootId.equals(execution.id()) && !events.isEmpty()) {
                ExecutionEvent first = events.getFirst();
                marks.add(new Mark(first.occurredAt(), execution.id(), Integer.MIN_VALUE, subagent()));
            }
            for (ExecutionEvent event : events) {
                ActivityUse use = event.eventType().isTool()
                    ? new ActivityUse(TOOL, event.toolName(), null)
                    : event.eventType() == ExecutionEventType.SUBAGENT_STARTED ? subagent() : null;
                if (use != null) {
                    marks.add(new Mark(event.occurredAt(), execution.id(), event.sequence(), use));
                }
            }
        }
        marks.sort(Comparator.comparing(Mark::at, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(Mark::executionId)
            .thenComparingInt(Mark::sequence));
        Map<String, ActivityUse> distinct = new LinkedHashMap<>();
        for (Mark mark : marks) {
            distinct.putIfAbsent(mark.use().kind() + ":" + mark.use().toolName(), mark.use());
        }
        return List.copyOf(distinct.values());
    }


    private static ActivityUse subagent() {
        return new ActivityUse(SUBAGENT, null, null);
    }
}
