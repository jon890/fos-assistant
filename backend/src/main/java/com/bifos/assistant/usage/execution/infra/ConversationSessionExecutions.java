package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 대화의 한 Hermes session 으로 돈 실행을 묻는 저장소다. 대화방이 날짜를 넘었는지와 요약을 입력에 다시 붙일지 정할 때 쓴다
 * (docs/features/agent-team.md 의 「대화방」). {@code AgentExecutionRepository} 가 파일 길이 상한에 닿아 따로 둔다.
 */
public interface ConversationSessionExecutions extends Repository<AgentExecution, Long> {

    /**
     * 그 대화에서 그 session 으로 돈 루트 실행 가운데 가장 이른 시작 시각이다. 없으면 null 이다. 대화의 루트 session 으로 부른다.
     * {@code (hermes_session_id, status)} 색인을 탄다.
     */
    @Query("""
            select min(e.startedAt) from AgentExecution e
             where e.hermesSessionId = :sessionId and e.conversationId = :conversationId and e.parentExecutionId is null
            """)
    Instant findFirstRootStartedAt(@Param("conversationId") Long conversationId, @Param("sessionId") String sessionId);


    /** 그 대화에서 그 session 으로 그 상태인 실행이 있는지. */
    boolean existsByHermesSessionIdAndConversationIdAndStatus(
        String hermesSessionId, Long conversationId, ExecutionStatus status);
}
