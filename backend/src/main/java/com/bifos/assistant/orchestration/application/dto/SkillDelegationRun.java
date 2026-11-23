package com.bifos.assistant.orchestration.application.dto;

import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;

/**
 * 예약 작업이 맡긴 실행이 끝난 결과다(docs/features/assistant-hub/spec.md 의 DECISION27).
 *
 * @param output 그 실행의 답. 없으면 null
 */
public record SkillDelegationRun(Long executionId, ExecutionStatus status, String output) {
}
