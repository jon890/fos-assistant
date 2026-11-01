package com.bifos.assistant.proactive.surface.presentation;

import com.bifos.assistant.proactive.autonomy.domain.AutonomyDecision;
import com.bifos.assistant.proactive.evaluation.domain.CandidateJudgement;
import com.bifos.assistant.proactive.evaluation.domain.DecisionCandidate;
import com.bifos.assistant.proactive.evaluation.domain.ValueEvaluation;
import com.bifos.assistant.proactive.autonomy.domain.type.AutonomyExecutionStatus;
import com.bifos.assistant.proactive.autonomy.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.autonomy.domain.type.AutonomyReason;
import com.bifos.assistant.proactive.evaluation.domain.type.DecisionFailure;
import com.bifos.assistant.proactive.evaluation.domain.type.DecisionOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 에이전트 화면의 살펴보기 절이 주고받는 모양이다.
 *
 * <p>실행 번호, profile, 오류 코드 원문, 토큰, 금액은 싣지 않는다(ADR-063). 예외는 발견 목록의 {@code executionId} 다. 답 메시지에 이미
 * 보이는 실행 번호이고, 화면이 발견을 그릴 답 아래 자리를 정하는 데만 쓴다({@code ChatDtos.MemoryCaptureView} 와 같다).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProactiveCheckDtos {

    /** 설치된 adapter 이름만 받는다. 모델, profile, 후보 본문은 요청으로 고르지 않는다. */
    public record EvaluationRequest(
        @NotBlank @Size(max = 64) String provider) {
    }

    /**
     * 실행 번호와 provider/model 원문은 내부 평가 기록에만 남긴다.
     *
     * @param failure {@code FALLBACK} 의 실패 코드. 아니면 null
     * @param candidates 평가 때 고정한 후보 스냅샷. 근거 주소와 기대 효과는 싣지 않는다
     */
    public record EvaluationResponse(
        Long id,
        Long replayOfId,
        DecisionOutcome outcome,
        DecisionFailure failure,
        List<CandidateView> candidates,
        List<CandidateJudgement> judgements,
        List<Long> orderedCandidateIds,
        String explanation) {

        static EvaluationResponse from(ValueEvaluation row) {
            var evidence = row.evidence();
            var result = evidence.result();
            return new EvaluationResponse(
                row.id(),
                row.replayOfId(),
                row.outcome(),
                result.failure(),
                evidence.state().candidates().stream()
                    .map(CandidateView::from)
                    .toList(),
                result.judgements(),
                result.orderedCandidateIds(),
                result.explanation());
        }
    }

    /** 평가 때 고정한 후보 하나다. 식별자, 문제 키, 문제 글, 행동 종류, 부작용 힌트만 싣는다. */
    public record CandidateView(Long id, String problemKey, String problem, String actionType, String sideEffect) {

        static CandidateView from(DecisionCandidate candidate) {
            return new CandidateView(
                candidate.candidateId(),
                candidate.problemKey(),
                candidate.problem(),
                candidate.actionType(),
                candidate.sideEffect());
        }
    }

    /**
     * 후보 하나의 행동 정책 판정이다. 시작한 살펴보기 식별자와 판정 입력 원문은 싣지 않는다.
     *
     * @param executionStatus {@code EXECUTE} 가 아니면 null
     */
    public record AutonomyDecisionResponse(
        Long id,
        Long candidateId,
        AutonomyLevel level,
        List<AutonomyReason> reasons,
        AutonomyExecutionStatus executionStatus) {

        static AutonomyDecisionResponse from(AutonomyDecision row) {
            return new AutonomyDecisionResponse(
                row.id(), row.candidateId(), row.level(), row.reasons(), row.executionStatus());
        }
    }

    /** 사용자의 자동 실행 동의다. */
    public record AutonomyPreferenceBody(@NotNull Boolean readOnlyExecution) {
    }

    /** @param reaction {@code ACCEPTED} 나 {@code DISMISSED}. 모르는 값은 서비스가 400 으로 거절한다 */
    public record DecisionReactionRequest(@NotBlank String reaction) {
    }
}
