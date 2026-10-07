package com.bifos.assistant.proactive.application;

import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.proactive.domain.DecisionCandidate;
import com.bifos.assistant.proactive.domain.DecisionEvidence;
import com.bifos.assistant.proactive.domain.DecisionProviderInfo;
import com.bifos.assistant.proactive.domain.DecisionQuestion;
import com.bifos.assistant.proactive.domain.DecisionResult;
import com.bifos.assistant.proactive.domain.DecisionState;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.DecisionFailure;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 짧은 저장 트랜잭션만 연다. 모델을 기다리는 동안 DB 연결이나 행 잠금을 쥐지 않는다. */
@Service
@RequiredArgsConstructor
public class ValueEvaluationStore {

    private final ProactiveCheckRepository checks;
    private final ProactiveCheckProblemRepository problems;
    private final ValueEvaluationRepository evaluations;
    private final ConversationRepository conversations;
    private final Clock clock;

    @Transactional
    public ValueEvaluation begin(Long userId, Long checkId, String provider) {
        requireCheck(userId, checkId);
        List<DecisionCandidate> candidates =
                problems.findByCheckIdAndStatusOrderByIdAsc(checkId, ProblemStatus.ACCEPTED).stream()
                        .map(DecisionCandidate::from)
                        .toList();
        if (candidates.size() > 3) {
            throw conflict();
        }
        DecisionState state = new DecisionState(1, clock.instant(), candidates);
        return insert(userId, checkId, null, state, ValueEvaluator.QUESTIONS, provider);
    }

    @Transactional
    public ValueEvaluation beginReplay(Long userId, Long sourceId, String provider) {
        ValueEvaluation source = read(userId, sourceId);
        if (source.outcome() == DecisionOutcome.RUNNING) {
            throw conflict();
        }
        return insert(
                userId,
                source.checkId(),
                source.id(),
                source.evidence().state(),
                source.evidence().questions(),
                provider);
    }

    @Transactional(readOnly = true)
    public ValueEvaluation read(Long userId, Long id) {
        ValueEvaluation row = evaluations.findByIdAndUserId(id, userId).orElseThrow(ValueEvaluationStore::notFound);
        requireCheck(userId, row.checkId());
        return row;
    }

    @Transactional
    public ValueEvaluation finish(Long userId, Long id, DecisionEvidence evidence) {
        ValueEvaluation row = read(userId, id);
        row.finish(evidence);
        return evaluations.save(row);
    }

    /** JSON 변환 전에 식별자만 읽어 손상된 한 줄이 다른 줄의 복구를 막지 않게 한다. */
    @Transactional(readOnly = true)
    public List<Long> findRunningIds() {
        return evaluations.findIdsByOutcome(DecisionOutcome.RUNNING);
    }

    /** Web server 가 열리기 전 한 줄씩 별도 트랜잭션으로 원래 입력을 보존하고 중단 상태만 바꾼다. */
    @Transactional
    public void recover(Long id) {
        evaluations
                .findById(id)
                .filter(row -> row.outcome() == DecisionOutcome.RUNNING)
                .ifPresent(row -> {
                    DecisionEvidence input = row.evidence();
                    row.finish(new DecisionEvidence(
                            input.state(),
                            input.questions(),
                            input.provider(),
                            DecisionResult.fallback(DecisionFailure.INTERRUPTED)));
                    evaluations.save(row);
                });
    }

    private ValueEvaluation insert(
            Long userId,
            Long checkId,
            Long replayOfId,
            DecisionState state,
            List<DecisionQuestion> questions,
            String provider) {
        DecisionEvidence evidence = new DecisionEvidence(
                state,
                questions,
                new DecisionProviderInfo(provider, "unknown", null, null, null, null, null),
                DecisionResult.running());
        return evaluations.save(ValueEvaluation.of(checkId, userId, replayOfId, evidence, clock.instant()));
    }

    private void requireCheck(Long userId, Long checkId) {
        ProactiveCheck check = checks.findByIdAndUserId(checkId, userId).orElseThrow(ValueEvaluationStore::notFound);
        if (conversations
                .findByIdAndUserIdAndDeletedAtIsNull(check.conversationId(), userId)
                .isEmpty()) {
            throw notFound();
        }
        if (check.status() != CheckStatus.SUCCEEDED) {
            throw conflict();
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.VALUE_EVALUATION_NOT_FOUND, "value evaluation not found");
    }

    private static ApiException conflict() {
        return new ApiException(ErrorCode.VALUE_EVALUATION_STATE_CONFLICT, "value evaluation is not ready");
    }
}
