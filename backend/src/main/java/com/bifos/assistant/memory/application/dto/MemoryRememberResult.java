package com.bifos.assistant.memory.application.dto;

import java.util.List;

/**
 * {@code memory_remember} 한 번의 결과와 그 항목 번호다.
 *
 * @param memoryId 만들거나 고치거나 이미 있던 항목의 번호. 저장하지 않은 결과면 null 이다
 * @param replaced 새 항목이 대신한 옛 항목 번호. 제안이면 받아들일 때 대신할 번호다. 합치기가 아니면 빈 목록이다
 * @param notReplaced 대신하지 못한 번호. 까닭은 가리지 않는다(docs/features/memory/spec.md 의 DECISION27)
 */
public record MemoryRememberResult(MemoryRememberOutcome outcome, Long memoryId, List<Long> replaced, List<Long> notReplaced) {

    public MemoryRememberResult {
        replaced = replaced == null ? List.of() : List.copyOf(replaced);
        notReplaced = notReplaced == null ? List.of() : List.copyOf(notReplaced);
    }


    public MemoryRememberResult(MemoryRememberOutcome outcome, Long memoryId) {
        this(outcome, memoryId, List.of(), List.of());
    }


    public static MemoryRememberResult of(MemoryRememberOutcome outcome) {
        return new MemoryRememberResult(outcome, null);
    }
}
