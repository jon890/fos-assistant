package com.bifos.assistant.chat.turn.application.dto;

import com.bifos.assistant.agent.application.dto.AgentLabel;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;

/**
 * 대화의 turn 이 바로 맡긴 위임 하나다. 답 아래의 맡긴 줄, 결과 도착 줄, {@code delegation} 사건이 같은 모양으로 싣는다
 * (docs/features/agent-team/design.md 의 「PR2 협업 보이기」).
 *
 * @param executionId 위임 실행 번호
 * @param parentExecutionId 맡긴 turn 의 실행 번호. 화면이 이 번호의 답 아래에 줄을 그린다
 * @param status 위임 실행의 지금 상태
 * @param agent 맡은 에이전트의 이름표. 지운 에이전트면 네 칸이 비어 있다
 */
public record DelegationLine(Long executionId, Long parentExecutionId, ExecutionStatus status, AgentLabel agent) {
}
