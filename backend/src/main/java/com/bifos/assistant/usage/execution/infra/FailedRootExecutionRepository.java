package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.FailedExecutionRow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 관리자 요약이 세는 실패한 루트 실행의 질의다.
 *
 * <p>{@link AgentExecutionRepository} 가 파일 길이 상한에 닿아 따로 둔다. 같은 {@code agent_execution} 을 읽는다.
 */
public interface FailedRootExecutionRepository extends Repository<AgentExecution, Long> {

    /**
     * 사용자들의 실패한 루트 실행을 센다.
     *
     * <p>실패는 도는 중이 아니고, 상태가 실패이거나 오류 코드가 있는 실행이다. 웹의 실행 기록이 실패 배지를 붙이는
     * 규칙({@code web/src/lib/execution-status.ts})과 같다. 자식 실행은 세지 않는다. 실행 기록 목록과 같은 단위다.
     */
    @Query("""
            select count(e) from AgentExecution e
            where e.userId in :userIds and e.rootExecutionId is null and e.startedAt >= :since
              and e.status <> com.bifos.assistant.usage.execution.domain.type.ExecutionStatus.RUNNING
              and (e.status = com.bifos.assistant.usage.execution.domain.type.ExecutionStatus.FAILED
                or e.errorCode is not null)
            """)
    long countFailedRootsSince(@Param("userIds") Collection<Long> userIds, @Param("since") Instant since);


    /** {@link #countFailedRootsSince} 와 같은 실패를 시작 시각, 번호 내림차순으로 읽고 에이전트 이름을 붙인다. */
    @Query("""
            select new com.bifos.assistant.usage.execution.domain.FailedExecutionRow(
                e.id, e.userId, a.name, e.startedAt, e.errorCode)
            from AgentExecution e
                left join Agent a on a.id = e.agentId
            where e.userId in :userIds and e.rootExecutionId is null and e.startedAt >= :since
              and e.status <> com.bifos.assistant.usage.execution.domain.type.ExecutionStatus.RUNNING
              and (e.status = com.bifos.assistant.usage.execution.domain.type.ExecutionStatus.FAILED
                or e.errorCode is not null)
            order by e.startedAt desc, e.id desc
            """)
    List<FailedExecutionRow> findFailedRootsSince(
        @Param("userIds") Collection<Long> userIds, @Param("since") Instant since, Pageable pageable);
}
