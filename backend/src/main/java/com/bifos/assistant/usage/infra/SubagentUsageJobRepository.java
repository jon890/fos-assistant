package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.SubagentUsageJob;
import java.time.Instant;
import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubagentUsageJobRepository extends JpaRepository<SubagentUsageJob, Long> {
    boolean existsByExecutionIdAndChildSessionId(Long executionId, String childSessionId);

    List<SubagentUsageJob> findByExecutionIdIn(Collection<Long> executionIds);

    List<SubagentUsageJob> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(String status, Instant at);
}
