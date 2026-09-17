package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.CostByAgent;
import com.bifos.assistant.usage.domain.CostByDay;
import com.bifos.assistant.usage.domain.CostByFingerprint;
import com.bifos.assistant.usage.domain.CostByModel;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.domain.MonthlyCostDetail;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, Long> {

    List<AgentExecution> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    List<AgentExecution> findByStatus(ExecutionStatus status);

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

    /** 환산액과 실제 청구액을 함께 합친다. RUNNING 은 빠진다. */
    @Query(
            """
            select new com.bifos.assistant.usage.domain.MonthlyCostDetail(
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(case when e.estimatedCostMicros is null then 0L else 1L end),
                sum(case when e.estimatedCostMicros is null then 1L else 0L end),
                sum(case when e.actualCostMicros is null then 1L else 0L end))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.ExecutionStatus.RUNNING
            """)
    MonthlyCostDetail sumCostDetailBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 에이전트별 합계. RUNNING 은 빠진다.
     *
     * <p>에이전트 이름을 같은 질의에서 함께 읽는다. 줄마다 에이전트를 다시 찾으면 축 하나에 질의가
     * 실행 수만큼 늘어난다.
     *
     * <p>실행 줄을 전부 센다. 자식 토큰이 부모의 usage 에 포함되지 않는 것을 ADR-016 이 실측으로
     * 확정했으므로, 전부 세는 것이 실제 사용량이고 두 번 세어지지 않는다.
     */
    @Query(
            """
            select new com.bifos.assistant.usage.domain.CostByAgent(
                e.agentId,
                a.code,
                a.name,
                count(e),
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(e.inputTokens),
                sum(e.outputTokens),
                avg(e.contextChars),
                min(e.startedAt),
                max(e.startedAt))
            from AgentExecution e
                left join Agent a on a.id = e.agentId
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.ExecutionStatus.RUNNING
            group by e.agentId, a.code, a.name
            order by sum(coalesce(e.estimatedCostMicros, 0L)) desc, e.agentId asc
            """)
    List<CostByAgent> sumByAgentBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /** 모델과 provider 별 합계. RUNNING 은 빠진다. */
    @Query(
            """
            select new com.bifos.assistant.usage.domain.CostByModel(
                e.provider,
                e.model,
                count(e),
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(e.inputTokens),
                sum(e.outputTokens),
                avg(e.contextChars),
                min(e.startedAt),
                max(e.startedAt))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.ExecutionStatus.RUNNING
            group by e.provider, e.model
            order by sum(coalesce(e.estimatedCostMicros, 0L)) desc, e.model asc
            """)
    List<CostByModel> sumByModelBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 날짜별 합계. 하루 단위로 묶는다. RUNNING 은 빠진다.
     *
     * <p>저장된 값은 시간대가 없는 순간이고 화면이 보이려는 날짜는 가족이 사는 곳의 달력이라, 날짜를
     * 뽑기 전에 9시간을 더한다. {@code Asia/Seoul} 은 일광 절약 시간이 없어 표준시와의 차이가 늘 9시간
     * 이다. 그 값을 파라미터로 넘기면 H2 가 {@code group by} 의 식을 {@code select} 의 식과 같은
     * 것으로 보지 않아 질의가 거절된다.
     */
    @Query(
            """
            select new com.bifos.assistant.usage.domain.CostByDay(
                year(e.startedAt + 9 hour),
                month(e.startedAt + 9 hour),
                day(e.startedAt + 9 hour),
                count(e),
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(e.inputTokens),
                sum(e.outputTokens),
                avg(e.contextChars),
                min(e.startedAt),
                max(e.startedAt))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.ExecutionStatus.RUNNING
            group by
                year(e.startedAt + 9 hour),
                month(e.startedAt + 9 hour),
                day(e.startedAt + 9 hour)
            order by
                year(e.startedAt + 9 hour) asc,
                month(e.startedAt + 9 hour) asc,
                day(e.startedAt + 9 hour) asc
            """)
    List<CostByDay> sumByDayBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 설정 지문별 합계. 무엇이 달라져서 비용이 움직였는지 본다. RUNNING 은 빠진다.
     *
     * <p>지문이 비어 있는 실행은 한 묶음으로 모으지 않고 통째로 뺀다. 지문을 모르는 것끼리 묶어도
     * 견줄 것이 없기 때문이다.
     */
    @Query(
            """
            select new com.bifos.assistant.usage.domain.CostByFingerprint(
                e.runtimeFingerprint,
                count(e),
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(e.inputTokens),
                sum(e.outputTokens),
                avg(e.contextChars),
                min(e.startedAt),
                max(e.startedAt))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.ExecutionStatus.RUNNING
                and e.runtimeFingerprint is not null
            group by e.runtimeFingerprint
            order by max(e.startedAt) desc, e.runtimeFingerprint asc
            """)
    List<CostByFingerprint> sumByFingerprintBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);
}
