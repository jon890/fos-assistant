package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import org.springframework.data.repository.Repository;

/**
 * 대화에서 도는 맡긴 실행을 묻는다. {@link AgentExecutionRepository} 가 파일 길이 상한에 닿아 따로 둔다.
 */
public interface RunningDelegationQueries extends Repository<AgentExecution, Long> {

    /**
     * 그 대화에 그 상태이고 {@code delegation_key} 가 있는 실행, 곧 {@code agent_delegate} 로 맡긴 실행이 있는가. 대화 turn 의 루트는
     * {@code delegation_key} 가 없어 세지 않는다. steer 말풍선에 맡긴 도우미 안내를 붙일지 정할 때 쓴다(ADR-20261116 / run-steer).
     */
    boolean existsByConversationIdAndStatusAndDelegationKeyIsNotNull(Long conversationId, ExecutionStatus status);
}
