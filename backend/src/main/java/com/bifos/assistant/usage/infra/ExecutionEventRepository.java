package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import java.util.Collection;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {

    @Query("""
            select event from ExecutionEvent event, AgentExecution parent
            where event.executionId = parent.id and parent.finishedAt is not null
            and parent.finishedAt >= :since and event.eventType = 'SUBAGENT_STARTED'
            and event.hermesSessionId is not null
            and not exists (select job.id from SubagentUsageJob job
                where job.executionId = event.executionId and job.childSessionId = event.hermesSessionId)
            and not exists (select done.id from ExecutionEvent done
                where done.executionId = event.executionId and done.hermesSessionId = event.hermesSessionId
                and done.eventType = 'SUBAGENT_COMPLETED')
            order by event.id
            """)
    List<ExecutionEvent> findUnscheduledChildren(@Param("since") Instant since, Pageable pageable);

    boolean existsByExecutionIdAndHermesSessionIdAndEventType(Long executionId, String hermesSessionId,
            ExecutionEventType eventType);

    @Query("select coalesce(max(event.sequence), 0) from ExecutionEvent event where event.executionId = :id")
    int lastSequence(@Param("id") Long id);

    /**
     * 나무에 담긴 실행들의 사건을 일어난 순서대로 한 번에 읽는다. {@code uk_execution_event_seq} 를
     * 그대로 쓴다.
     *
     * <p>노드마다 따로 읽으면 질의가 노드 수만큼 늘어난다. 실행 번호가 비어 있으면 부르지 않는다. 빈
     * {@code in} 절은 데이터베이스마다 다르게 동작한다.
     */
    List<ExecutionEvent> findByExecutionIdInOrderByExecutionIdAscSequenceAsc(
            Collection<Long> executionIds);
}
