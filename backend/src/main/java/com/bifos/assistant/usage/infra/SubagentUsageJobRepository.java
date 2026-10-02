package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.SubagentLedgerRow;
import com.bifos.assistant.usage.domain.SubagentUsageJob;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubagentUsageJobRepository extends JpaRepository<SubagentUsageJob, Long> {
    boolean existsByProfileNameAndChildSessionId(String profileName, String childSessionId);

    List<SubagentUsageJob> findByExecutionIdIn(Collection<Long> executionIds);

    List<SubagentUsageJob> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            String status, Instant at);

    /**
     * 한 구간에 시작한 실행의 native 자식 원장 줄을 부모 실행의 축 값과 함께 읽는다.
     *
     * <p>실행 합계와 같은 조건으로 부모를 거른다. 부모가 RUNNING 이면 그 자식도 빠진다. 어느 줄을 금액에
     * 더하고 어느 줄을 미확인으로 세는지는 {@code UsageSummaryService} 가 줄의 상태로 정한다(ADR-059).
     *
     * <p>고른 칸의 순서가 {@link SubagentLedgerRow} 의 칸 순서와 같아야 한다. Spring Data 가 그 순서대로
     * record 를 만든다.
     */
    @Query("""
            select
                e.agentId,
                e.startedAt,
                e.runtimeFingerprint,
                j.status,
                j.provider,
                j.model,
                j.inputTokens,
                j.cacheReadTokens,
                j.cacheWriteTokens,
                j.outputTokens,
                j.estimatedCostMicros,
                j.actualCostMicros
            from SubagentUsageJob j, AgentExecution e
            where e.id = j.executionId
                and e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> 'RUNNING'
            order by j.id
            """)
    List<SubagentLedgerRow> findLedgerRows(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);
}
