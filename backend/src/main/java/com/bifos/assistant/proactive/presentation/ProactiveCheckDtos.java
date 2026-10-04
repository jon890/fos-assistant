package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.proactive.application.model.CheckStatusView;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
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
     * @param finishedAt 끝난 시각. 돌고 있으면 null
     */
    public record LastCheckView(String status, String outcome, Instant startedAt, Instant finishedAt) {

        static LastCheckView from(ProactiveCheck check) {
            return new LastCheckView(
                    check.status().name(),
                    check.outcome() == null ? null : check.outcome().name(),
                    check.startedAt(),
                    check.finishedAt());
        }
    }
}
