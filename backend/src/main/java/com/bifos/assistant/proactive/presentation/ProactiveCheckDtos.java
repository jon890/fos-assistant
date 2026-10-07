package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.proactive.application.model.CheckStatusView;
import com.bifos.assistant.proactive.domain.CandidateJudgement;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 에이전트 화면의 살펴보기 절이 주고받는 모양이다.
 *
 * <p>실행 번호, profile, 오류 코드 원문, 토큰, 금액은 싣지 않는다(ADR-063).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProactiveCheckDtos {

    /** 설치된 adapter 이름만 받는다. 모델, profile, 후보 본문은 요청으로 고르지 않는다. */
    public record EvaluationRequest(
            @NotBlank @Size(max = 64) String provider) {}

    /** 실행 번호와 provider/model 원문은 내부 평가 기록에만 남긴다. */
    public record EvaluationResponse(
            Long id,
            Long replayOfId,
            DecisionOutcome outcome,
            List<CandidateJudgement> judgements,
            List<Long> orderedCandidateIds,
            String explanation) {

        static EvaluationResponse from(ValueEvaluation row) {
            var result = row.evidence().result();
            return new EvaluationResponse(
                    row.id(),
                    row.replayOfId(),
                    row.outcome(),
                    result.judgements(),
                    result.orderedCandidateIds(),
                    result.explanation());
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
}
