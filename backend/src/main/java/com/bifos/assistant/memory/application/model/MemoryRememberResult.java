package com.bifos.assistant.memory.application.model;

/**
 * {@code memory_remember} 한 번의 결과와 그 항목 번호다.
 *
 * @param memoryId 만들거나 고치거나 이미 있던 항목의 번호. 저장하지 않은 결과면 null 이다
 */
public record MemoryRememberResult(MemoryRememberOutcome outcome, Long memoryId) {

    public static MemoryRememberResult of(MemoryRememberOutcome outcome) {
        return new MemoryRememberResult(outcome, null);
    }
}
