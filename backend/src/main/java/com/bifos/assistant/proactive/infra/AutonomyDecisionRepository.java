package com.bifos.assistant.proactive.infra;

import java.util.List;
import java.util.Collection;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutonomyDecisionRepository extends JpaRepository<AutonomyDecision, Long> {

    boolean existsByExecutionKey(String executionKey);

    List<AutonomyDecision> findByUserIdAndEvaluationIdInOrderByIdAsc(Long userId, Collection<Long> evaluationIds);

    /** 그 사용자의 판정 가운데 그 살펴보기들을 자동 실행으로 시작한 것. */
    List<AutonomyDecision> findByUserIdAndExecutionCheckIdIn(Long userId, Collection<Long> executionCheckIds);
}
