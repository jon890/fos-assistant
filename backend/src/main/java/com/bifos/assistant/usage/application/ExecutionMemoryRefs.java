package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionContextSourceRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행들이 본문을 받은 Memory 항목을 실행 기록에서 모은다(ADR-20261008 / memory-facts).
 *
 * <p>두 재료를 쓴다. 실행에 실은 항목(항상 층과 개인 사실 구역에서 본문까지 실은 줄)과, 실행이 {@code memory_read} 로 읽은
 * 항목이다. 제목만 실은 줄과 빠진 줄은 본문을 받지 않았으므로 넣지 않는다.
 *
 * <p>{@code usage} 는 {@code context} 와 {@code memory} 를 쓰지 못하므로 출처 이름은 문자열로 비교한다.
 */
@Component
@RequiredArgsConstructor
public class ExecutionMemoryRefs {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MEMORY_REF_PREFIX = "memory:";
    private static final String READ_TOOL = "memory_read";
    private static final String INLINE = "INLINE";

    private final ExecutionContextSourceRepository contextSources;
    private final ExecutionEventRepository events;
    private final AgentExecutionRepository executions;

    /**
     * 실행마다 실은 순서, 이어서 읽은 순서로 참조를 낸다. 같은 실행의 같은 항목은 앞의 것 하나만 남기고, 실행 번호 오름차순이다.
     * 실행 번호가 비면 아무것도 읽지 않는다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작한다.
     */
    @Transactional(readOnly = true)
    public List<ExecutionMemoryRef> of(Collection<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<Long, String>> viaByExecution = new LinkedHashMap<>();
        for (ExecutionContextSource source :
                contextSources.findByIdExecutionIdInOrderByIdExecutionIdAscIdPositionAsc(executionIds)) {
            String via = loadedVia(source);
            Long memoryId = memoryIdOf(source.sourceRef());
            if (via != null && memoryId != null) {
                viaByExecution
                        .computeIfAbsent(source.executionId(), id -> new LinkedHashMap<>())
                        .putIfAbsent(memoryId, via);
            }
        }
        for (ExecutionEvent event : events.findByExecutionIdInAndEventTypeOrderByExecutionIdAscSequenceAsc(
                executionIds, ExecutionEventType.TOOL_STARTED)) {
            Long memoryId = readMemoryId(event);
            if (memoryId != null) {
                viaByExecution
                        .computeIfAbsent(event.executionId(), id -> new LinkedHashMap<>())
                        .putIfAbsent(memoryId, "READ");
            }
        }
        Map<Long, Long> agentIds = executions.findAllById(viaByExecution.keySet()).stream()
                .filter(execution -> execution.agentId() != null)
                .collect(Collectors.toMap(AgentExecution::id, AgentExecution::agentId));
        List<ExecutionMemoryRef> refs = new ArrayList<>();
        viaByExecution.keySet().stream()
                .sorted()
                .forEach(executionId -> viaByExecution
                        .get(executionId)
                        .forEach((memoryId, via) -> refs.add(
                                new ExecutionMemoryRef(executionId, agentIds.get(executionId), memoryId, via))));
        return refs;
    }

    /** 본문까지 실은 항상 층과 개인 사실 구역의 줄만 길 이름을 낸다. 나머지는 null 이다. */
    private static String loadedVia(ExecutionContextSource source) {
        if (!INLINE.equals(source.bodyMode())) {
            return null;
        }
        return switch (source.source()) {
            case "MEMORY_ALWAYS" -> "ALWAYS";
            case "MEMORY_FACTS" -> "FACTS";
            default -> null;
        };
    }

    private static Long memoryIdOf(String sourceRef) {
        if (!sourceRef.startsWith(MEMORY_REF_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(sourceRef.substring(MEMORY_REF_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code memory_read} 시작 사건의 {@code {"id":<정수>}} 에서 번호를 꺼낸다. 읽지 못하면 관측용 기록이라 건너뛴다. */
    private static Long readMemoryId(ExecutionEvent event) {
        if (event.toolName() == null || !event.toolName().endsWith(READ_TOOL) || event.detail() == null) {
            return null;
        }
        try {
            JsonNode id = JSON.readTree(event.detail()).path("id");
            return id.isIntegralNumber() ? id.asLong() : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
