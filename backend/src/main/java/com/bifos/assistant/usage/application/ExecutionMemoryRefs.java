package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.application.model.MemoryUseVia;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionContextSourceRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행들이 본문을 받은 Memory 항목을 실행 기록에서 모은다(ADR-20261008 / memory-facts).
 *
 * <p>{@code execution_context_source} 의 본문까지 실은 줄만 읽는다. 조립이 실은 항상 층과 개인 사실 구역의 줄, 그리고
 * {@code memory_read} 처리가 읽은 뒤에 덧붙인 줄이다. 제목만 실은 줄과 빠진 줄은 본문을 받지 않았으므로 넣지 않는다.
 *
 * <p>{@code usage} 는 {@code context} 와 {@code memory} 를 쓰지 못하므로 출처 이름은 문자열로 비교한다.
 */
@Component
@RequiredArgsConstructor
public class ExecutionMemoryRefs {

    private static final String MEMORY_REF_PREFIX = "memory:";
    private static final String INLINE = "INLINE";
    private static final Map<String, MemoryUseVia> VIA_BY_SOURCE = Map.of(
            "MEMORY_ALWAYS", MemoryUseVia.ALWAYS,
            "MEMORY_FACTS", MemoryUseVia.FACTS,
            "MEMORY_READ", MemoryUseVia.READ);

    private final ExecutionContextSourceRepository contextSources;
    private final AgentExecutionRepository executions;

    /**
     * 실행마다 기록한 순서로 참조를 낸다. 같은 실행의 같은 항목은 앞의 것 하나만 남기고, 실행 번호 오름차순이다.
     * 실행 번호가 비면 아무것도 읽지 않는다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작한다.
     */
    @Transactional(readOnly = true)
    public List<ExecutionMemoryRef> of(Collection<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<Long, MemoryUseVia>> viaByExecution = new LinkedHashMap<>();
        for (ExecutionContextSource source :
                contextSources.findByIdExecutionIdInAndSourceInAndBodyModeOrderByIdExecutionIdAscIdPositionAsc(
                        executionIds, VIA_BY_SOURCE.keySet(), INLINE)) {
            Long memoryId = memoryIdOf(source.sourceRef());
            if (memoryId != null) {
                viaByExecution
                        .computeIfAbsent(source.executionId(), id -> new LinkedHashMap<>())
                        .putIfAbsent(memoryId, VIA_BY_SOURCE.get(source.source()));
            }
        }
        Map<Long, Long> agentIds = executions.findAllById(viaByExecution.keySet()).stream()
                .filter(execution -> execution.agentId() != null)
                .collect(Collectors.toMap(AgentExecution::id, AgentExecution::agentId));
        List<ExecutionMemoryRef> refs = new ArrayList<>();
        viaByExecution.forEach((executionId, vias) -> vias.forEach((memoryId, via) ->
                refs.add(new ExecutionMemoryRef(executionId, agentIds.get(executionId), memoryId, via))));
        return refs;
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
}
