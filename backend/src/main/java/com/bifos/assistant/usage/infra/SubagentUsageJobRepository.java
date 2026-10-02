package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.SubagentUsageJob;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubagentUsageJobRepository extends JpaRepository<SubagentUsageJob, Long> {
    boolean existsByProfileNameAndChildSessionId(String profileName, String childSessionId);

    List<SubagentUsageJob> findByExecutionIdIn(Collection<Long> executionIds);

    List<SubagentUsageJob> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            String status, Instant at);
}
