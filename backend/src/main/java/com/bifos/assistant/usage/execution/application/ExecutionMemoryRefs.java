package com.bifos.assistant.usage.execution.application;

import com.bifos.assistant.usage.execution.application.dto.ExecutionMemoryRef;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionContextSource;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionContextSourceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행들이 {@code memory_read} 로 본문을 읽은 Memory 항목을 실행 기록에서 모은다(ADR-20261008 / memory-facts).
 *
 * <p>{@code execution_context_source} 가운데 {@code memory_read} 처리가 읽은 뒤에 덧붙인 {@code MEMORY_READ} 줄만 읽는다. 조립이 넣은
 * 줄(항상 층, 요약 구역, 질문과 관련된 기억 구역)은 넣지 않는다. 답마다 같은 기억이 길게 나열되기 때문이다(사용자 결정 2026-11-21).
 *
 * <p>{@code usage} 는 {@code context} 와 {@code memory} 를 쓰지 못하므로 출처 이름은 문자열로 비교한다.
 */
@Component
@RequiredArgsConstructor
public class ExecutionMemoryRefs {

    private static final String MEMORY_REF_PREFIX = "memory:";
    private static final String INLINE = "INLINE";
    private static final Set<String> READ_SOURCES = Set.of("MEMORY_READ");

    private final ExecutionContextSourceRepository contextSources;
    private final AgentExecutionRepository executions;


    /**
     * 실행마다 읽은 순서로 참조를 낸다. 같은 실행이 같은 항목을 여러 번 읽었으면 하나만 남기고, 실행 번호 오름차순이다.
     * 실행 번호가 비면 아무것도 읽지 않는다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작한다.
     */
    @Transactional(readOnly = true)
    public List<ExecutionMemoryRef> readOf(Collection<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Set<Long>> readByExecution = new LinkedHashMap<>();
        for (ExecutionContextSource source : contextSources.findByIdExecutionIdInAndSourceInAndBodyModeOrderByIdExecutionIdAscIdPositionAsc(
            executionIds, READ_SOURCES, INLINE)) {
            Long memoryId = memoryIdOf(source.sourceRef());
            if (memoryId != null) {
                readByExecution.computeIfAbsent(source.executionId(), id -> new LinkedHashSet<>()).add(memoryId);
            }
        }
        Map<Long, Long> agentIds = executions.findAllById(readByExecution.keySet()).stream()
            .filter(execution -> execution.agentId() != null)
            .collect(Collectors.toMap(AgentExecution::id, AgentExecution::agentId));
        List<ExecutionMemoryRef> refs = new ArrayList<>();
        readByExecution.forEach((executionId, memoryIds) -> memoryIds.forEach(memoryId -> refs.add(new ExecutionMemoryRef(executionId, agentIds.get(executionId), memoryId))));
        return refs;
    }


    /** 그때 뒤에 한 번이라도 실리거나 읽힌 기억의 번호다. 제목만 실린 줄도 센다. */
    @Transactional(readOnly = true)
    public Set<Long> loadedSince(Instant since) {
        return contextSources.findMemoryRefsLoadedSince(since).stream()
            .map(ExecutionMemoryRefs::memoryIdOf)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
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
