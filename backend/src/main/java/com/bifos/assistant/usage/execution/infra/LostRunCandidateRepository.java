package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 실행 줄에서 Hermes 가 잃었을 수 있는 run 을 고른다. 실행 저장소가 길어 따로 둔다. */
public interface LostRunCandidateRepository extends Repository<AgentExecution, Long> {

    /**
     * 그 profile 의 실행 가운데 Hermes 가 잃었을 수 있는 run 번호를 최근에 끝난 순으로 읽는다.
     *
     * <p>새 실행을 보내기 전에 잃은 실행을 해제하려고 쓴다(docs/features/hermes-upgrade/design.md 의 「잃은 실행 미리 풀기」).
     * {@code finished_at} 색인을 탄다.
     */
    @Query("""
        select e.hermesRunId from AgentExecution e
        where e.profileName = :profileName
          and e.status = :status
          and e.hermesRunId is not null
          and e.errorCode in :errorCodes
          and e.finishedAt >= :since
        order by e.finishedAt desc
        """)
    List<String> findLostRunCandidates(
        @Param("profileName") String profileName,
        @Param("status") ExecutionStatus status,
        @Param("errorCodes") Collection<String> errorCodes,
        @Param("since") Instant since,
        Pageable pageable);
}
