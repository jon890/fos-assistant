package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 옛 평문 위임 답을 암호화하거나 되돌리는 배치가 대상 번호를 고르는 질의다(ADR-20261008 / data-encryption). */
public interface ExecutionOutputBackfillRepository extends Repository<AgentExecution, Long> {

    /** 평문으로 남은 위임 답의 실행 번호를 번호 순으로 읽는다. 답이 없거나 빈 줄, 주인이 없는 줄은 뺀다(ADR-20261008 / data-encryption). */
    @Query("""
            select e.id from AgentExecution e
            where e.outputKeyId is null and e.userId is not null and e.outputText is not null
                and length(e.outputText) > 0 and e.id > :after
            order by e.id
            """)
    List<Long> findPlainOutputIds(@Param("after") Long after, Pageable pageable);


    /** 암호문 답의 실행 번호를 번호 순으로 읽는다. 풀기 배치가 쓴다. */
    @Query("select e.id from AgentExecution e where e.outputKeyId is not null and e.id > :after order by e.id")
    List<Long> findSealedOutputIds(@Param("after") Long after, Pageable pageable);


    @Query("""
            select count(e) from AgentExecution e
            where e.outputKeyId is null and e.userId is not null and e.outputText is not null
                and length(e.outputText) > 0
            """)
    long countPlainOutputs();


    @Query("select count(e) from AgentExecution e where e.outputKeyId is not null")
    long countSealedOutputs();
}
