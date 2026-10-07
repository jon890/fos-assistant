package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.AutonomyDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutonomyDecisionRepository extends JpaRepository<AutonomyDecision, Long> {

    boolean existsByExecutionKey(String executionKey);
}
