package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ExecutionObservationRepository extends Repository<AgentExecution, Long> {
    /** 결과를 먼저 확정한 성공 실행의 관측 상태만 갱신한다. */
    @Modifying
    @Query("""
            update AgentExecution e set e.eventObservation = 'OBSERVED'
             where e.id = :id and e.status = 'SUCCEEDED' and e.eventObservation = 'INCOMPLETE'
            """)
    int completeLateEventObservation(@Param("id") Long id);
}
