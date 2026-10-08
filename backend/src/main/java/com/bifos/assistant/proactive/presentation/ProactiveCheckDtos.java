package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.proactive.application.model.CheckFindingView;
import com.bifos.assistant.proactive.application.model.CheckStatusView;
import com.bifos.assistant.proactive.application.model.EvaluationOverview;
import com.bifos.assistant.proactive.application.model.LoopSettingView;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
            @NotBlank @Size(max = 64) String provider) {}

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

    /** 관리자 화면이 고른 살펴보기다. 루트 실행과 점검 대화 식별자는 싣지 않는다. */
    public record CheckSummaryView(Long id, CheckTrigger trigger, Instant finishedAt, int acceptedCandidates) {}

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
    public record AutonomyPreferenceBody(@NotNull Boolean readOnlyExecution) {}

    /**
     * 에이전트 하나의 매일 루프 설정을 바꾼다.
     *
     * @param snoozedUntil 비우거나 지금부터 30일 안의 시각. 지난 시각은 비운 것과 같다
     */
    public record LoopSettingBody(@NotNull Boolean enabled, Instant snoozedUntil) {}

    /**
     * 에이전트 하나의 매일 루프 설정이다.
     *
     * @param available 설치가 매일 루프를 연다. 거짓이면 켤 수 없다
     * @param snoozedUntil 쉬는 끝 시각. 지금보다 뒤일 때만 싣는다
     */
    public record LoopSettingResponse(boolean available, boolean enabled, Instant snoozedUntil) {

        static LoopSettingResponse from(LoopSettingView view) {
            return new LoopSettingResponse(view.available(), view.enabled(), view.snoozedUntil());
        }
    }

    /**
     * 살펴보기 상태다.
     *
     * @param available 지금 살펴보기를 시작할 수 있다. {@code blockers} 가 비었을 때만 참이다
     * @param blockers 막는 까닭. 화면이 까닭마다 안내를 그린다
     * @param conversationId 요청자의 점검 대화의 공개 식별자. 없으면 null
     * @param lastCheck 요청자가 그 에이전트로 연 마지막 살펴보기. 없으면 null
     */
    public record StatusResponse(
            boolean available, List<BlockerView> blockers, UUID conversationId, LastCheckView lastCheck) {

        static StatusResponse from(CheckStatusView view) {
            return new StatusResponse(
                    view.readiness().available(),
                    view.readiness().blockers().stream().map(BlockerView::from).toList(),
                    view.conversationId(),
                    view.lastCheck() == null ? null : LastCheckView.from(view.lastCheck()));
        }
    }

    /**
     * 살펴보기를 시작했다.
     *
     * @param conversationId 결과가 남는 점검 대화의 공개 식별자
     */
    public record StartedResponse(UUID conversationId) {}

    /**
     * 막는 까닭 하나다.
     *
     * @param code {@code CheckBlockerCode} 의 이름
     * @param toolsets {@code TOOLSETS_NOT_ALLOWED} 일 때 끌 toolset 이름. 다른 까닭이면 빈 목록
     */
    public record BlockerView(String code, List<String> toolsets) {

        static BlockerView from(CheckBlocker blocker) {
            return new BlockerView(blocker.code().name(), blocker.toolsets());
        }
    }

    /**
     * 마지막 살펴보기다.
     *
     * @param status {@code CheckStatus} 의 이름
     * @param outcome {@code CheckOutcome} 의 이름. 성공했을 때만 있고 아니면 null
     * @param invalidReason {@code CheckInvalidReason} 의 이름. {@code outcome} 이 {@code INVALID_RESULT} 일 때만 있고 아니면
     *     null. 그 칸을 더하기 전에 끝난 살펴보기도 null 이다
     * @param finishedAt 끝난 시각. 돌고 있으면 null
     */
    public record LastCheckView(
            String status,
            String outcome,
            String invalidReason,
            String skippedReason,
            Instant startedAt,
            Instant finishedAt) {

        static LastCheckView from(ProactiveCheck check) {
            return new LastCheckView(
                    check.status().name(),
                    check.outcome() == null ? null : check.outcome().name(),
                    check.invalidReason() == null ? null : check.invalidReason().name(),
                    check.skippedReason() == null ? null : check.skippedReason().name(),
                    check.startedAt(),
                    check.finishedAt());
        }
    }

    /**
     * 점검 대화의 「새로 알릴 것」 발견과 지금 반응이다.
     *
     * @param dismissWindowDays 「관심 없음」 이 같은 주제를 내리는 기간. {@code digest-window} 를 하루 단위로 올림한 값이다
     * @param findings 오래된 것부터
     */
    public record CheckFindingsResponse(long dismissWindowDays, List<CheckFindingView> findings) {}

    /**
     * 발견 하나에 누른 단추다. 모르는 값은 서비스가 {@code VALIDATION_FAILED} 로 거절한다.
     *
     * @param reaction {@code ACCEPTED}, {@code POSTPONED}, {@code DISMISSED} 가운데 하나
     */
    public record FindingReactionRequest(@NotBlank String reaction) {}

    /** @param reaction {@code ACCEPTED} 나 {@code DISMISSED}. 모르는 값은 서비스가 400 으로 거절한다 */
    public record DecisionReactionRequest(@NotBlank String reaction) {}
}
