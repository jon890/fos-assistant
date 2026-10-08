package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.application.model.MemoryUseVia;

/**
 * 한 실행이 본문을 받은 Memory 항목 하나의 참조다(ADR-20261008 / memory-facts).
 *
 * @param executionId 실행 번호
 * @param agentId 그 실행의 에이전트 번호. 없는 실행이면 null 이다
 * @param memoryId Memory 번호
 * @param via 받은 길
 */
public record ExecutionMemoryRef(Long executionId, Long agentId, Long memoryId, MemoryUseVia via) {}
