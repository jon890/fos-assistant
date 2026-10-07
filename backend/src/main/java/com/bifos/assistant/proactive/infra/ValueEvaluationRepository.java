package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValueEvaluationRepository extends JpaRepository<ValueEvaluation, Long> {

    Optional<ValueEvaluation> findByIdAndUserId(Long id, Long userId);

    List<ValueEvaluation> findByOutcome(DecisionOutcome outcome);
}
