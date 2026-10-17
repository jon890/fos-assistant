package com.bifos.assistant.admin.proactive.presentation;

import com.bifos.assistant.proactive.application.model.EvaluationOverview;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.CandidateJudgement;
import com.bifos.assistant.proactive.domain.DecisionCandidate;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.AutonomyExecutionStatus;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionFailure;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 가치 평가 경로가 주고받는 모양이다. 사용자 경로와 계약이 따로 바뀔 수 있어 겹치는 record 는 {@code ProactiveCheckDtos} 에서 복사해 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProactiveAdminDtos {

    /**
     * 관리자 화면이 읽는 가치 평가 묶음이다.
     *
     * @param check 고를 살펴보기가 없으면 null
     * @param evaluation 아직 평가하지 않았으면 null
     * @param decisions 판정하지 않았거나 후보가 없었으면 빈 목록
     */
    public record EvaluationOverviewResponse(
        CheckSummaryView check, EvaluationResponse evaluation, List<AutonomyDecisionResponse> decisions) {

        static EvaluationOverviewResponse from(EvaluationOverview overview) {
            ProactiveCheck check = overview.check();
            return new EvaluationOverviewResponse(
                check == null
                    ? null
                    : new CheckSummaryView(
                        check.id(), check.trigger(), check.finishedAt(), overview.acceptedCandidates()),
                overview.evaluation() == null ? null : EvaluationResponse.from(overview.evaluation()),
                overview.decisions().stream()
                    .map(AutonomyDecisionResponse::from)
                    .toList());
        }
    }

    /** 관리자 화면이 고른 살펴보기다. 루트 실행과 점검 대화 식별자는 싣지 않는다. */
    public record CheckSummaryView(Long id, CheckTrigger trigger, Instant finishedAt, int acceptedCandidates) {
    }

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
}
