package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Hermes 내장 위임 결과를 대화 에이전트가 이미 읽었는지 묻는 저장소다(ADR-20261117 / native-delegation-wake).
 * {@code AgentExecutionRepository} 가 파일 길이 상한에 닿아 따로 둔다.
 */
public interface NativeResultReadChecks extends Repository<AgentExecution, Long> {

    /**
     * 그 대화에 그 에이전트로 Hermes 에 제출된 루트 실행 가운데 {@code after} 보다 늦게 시작한 것이 있는가.
     *
     * <p>그런 turn 은 Hermes 가 부모 session 에 남긴 결과 줄을 이력으로 읽었다고 본다. {@code @} 맡김 turn 은 에이전트가 달라 빠지고,
     * 제출 전에 실패한 실행은 {@code hermes_run_id} 가 비어 빠진다.
     */
    @Query("""
            select count(e) > 0 from AgentExecution e
             where e.conversationId = :conversationId and e.parentExecutionId is null and e.agentId = :agentId
               and e.hermesRunId is not null and e.startedAt > :after
            """)
    boolean existsSubmittedRootStartedAfter(
        @Param("conversationId") Long conversationId, @Param("agentId") Long agentId, @Param("after") Instant after);
}
