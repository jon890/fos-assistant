package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.domain.MonthlyCost;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, Long> {

    List<AgentExecution> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    List<AgentExecution> findByStatus(ExecutionStatus status);

    /** 뿌리와 그 자손을 한 번에 읽는다. 뿌리 자신은 rootExecutionId 가 null 이라 따로 읽는다. */
    List<AgentExecution> findByRootExecutionId(Long rootExecutionId);

    /** 이 번호들 중 자식을 가진 것만 낸다. 목록이 실행마다 세지 않게 한 번에 읽는다. */
    @Query("""
            select distinct e.parentExecutionId from AgentExecution e
            where e.parentExecutionId in :parentIds
            """)
    List<Long> findParentIdsHavingChildren(@Param("parentIds") Collection<Long> parentIds);

    /**
     * 한 구간의 환산 금액을 데이터베이스에서 합친다.
     *
     * <p>줄을 다 읽어 와서 더하지 않는 이유는, 한 달치 실행 수가 화면이 보여 주는 50줄보다 훨씬 많아질 수
     * 있기 때문이다. 금액이 비어 있는 줄은 합계에 더해지지 않고 따로 세어진다.
     */
    @Query(
            """
            select new com.bifos.assistant.usage.domain.MonthlyCost(
                sum(e.estimatedCostMicros),
                sum(case when e.estimatedCostMicros is null then 0L else 1L end),
                sum(case when e.estimatedCostMicros is null then 1L else 0L end))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.ExecutionStatus.RUNNING
            """)
    MonthlyCost sumCostBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);
}
