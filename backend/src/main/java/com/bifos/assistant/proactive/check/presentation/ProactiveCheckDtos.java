package com.bifos.assistant.proactive.check.presentation;

import com.bifos.assistant.proactive.check.application.dto.CheckBlocker;
import com.bifos.assistant.proactive.check.application.dto.CheckFindingView;
import com.bifos.assistant.proactive.check.application.dto.CheckStatusView;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import jakarta.validation.constraints.NotBlank;
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
    public record StartedResponse(UUID conversationId) {
    }

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
     * null. 그 칸을 더하기 전에 끝난 살펴보기도 null 이다
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
    public record CheckFindingsResponse(long dismissWindowDays, List<CheckFindingView> findings) {
    }

    /**
     * 발견 하나에 누른 단추다. 모르는 값은 서비스가 {@code VALIDATION_FAILED} 로 거절한다.
     *
     * @param reaction {@code ACCEPTED}, {@code POSTPONED}, {@code DISMISSED} 가운데 하나
     */
    public record FindingReactionRequest(@NotBlank String reaction) {
    }
}
