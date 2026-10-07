package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ValueEvaluationRepository extends JpaRepository<ValueEvaluation, Long> {

    Optional<ValueEvaluation> findByIdAndUserId(Long id, Long userId);

    @Query("select e.id from ValueEvaluation e where e.outcome = :outcome order by e.id")
    List<Long> findIdsByOutcome(DecisionOutcome outcome);

    /** 그 사용자가 그 살펴보기들의 후보로 남긴 평가. replay 시도도 함께 읽는다. */
    List<ValueEvaluation> findByUserIdAndCheckIdInOrderByIdAsc(Long userId, Collection<Long> checkIds);
}
