package com.bifos.assistant.usage.application;

/**
 * 한 실행이 본문을 받은 Memory 항목 하나의 참조다(ADR-20261008 / memory-facts).
 *
 * @param executionId 실행 번호
 * @param agentId 그 실행의 에이전트 번호. 없는 실행이면 null 이다
 * @param memoryId Memory 번호
 * @param via 받은 길. {@code ALWAYS} 는 항상 층, {@code FACTS} 는 개인 사실 구역, {@code READ} 는 {@code memory_read}
 */
public record ExecutionMemoryRef(Long executionId, Long agentId, Long memoryId, String via) {}
