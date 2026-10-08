package com.bifos.assistant.proactive.application;

import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 끝난 살펴보기의 판단 피드백 사건을 남긴다(ADR-20261007 decision-feedback).
 *
 * <p>사용자가 연 살펴보기와 매일 깨우기는 검사한 보고를 남겼을 때만 {@code SURFACED} 다. 알릴 것이 없어 보고를 남기지 않은 침묵은 사건이
 * 아니다. 보고를 보이면 그 살펴보기의 「새로 알릴 것」 발견마다 {@code SURFACED} 도 남긴다. 자동 실행({@code AUTONOMY})은 사용자에게
 * 보이지 않으므로 실행 결과만 남긴다.
 */
@Component
@RequiredArgsConstructor
public class CheckFeedback {

    private final DecisionFeedbackRecorder feedback;
    private final ProactiveCheckRepository checks;
    private final CheckFindingReactions reactions;
    private final Clock clock;

    /**
     * 줄을 적은 뒤에 부른다. 저장된 줄을 다시 읽어 판정한다. 끝난 상태를 저장하지 못했으면 아직 도는 줄이라 남기지 않고, 기동 정리가 닫을 때
     * 남긴다.
     */
    public void ended(ProactiveCheck finished) {
        ProactiveCheck check =
                finished.id() == null ? null : checks.findById(finished.id()).orElse(null);
        if (check == null || check.status() == CheckStatus.RUNNING) {
            return;
        }
        if (check.trigger() == CheckTrigger.AUTONOMY) {
            boolean succeeded =
                    check.status() == CheckStatus.SUCCEEDED && check.outcome() != CheckOutcome.INVALID_RESULT;
            String reason = succeeded
                    ? null
                    : check.status() == CheckStatus.SUCCEEDED ? CheckOutcome.INVALID_RESULT.name() : check.errorCode();
            feedback.record(entry(
                            check,
                            succeeded ? FeedbackEventType.EXECUTION_SUCCEEDED : FeedbackEventType.EXECUTION_FAILED)
                    .reason(reason == null && !succeeded ? check.status().name() : reason));
            return;
        }
        if (check.report() != null) {
            feedback.record(entry(check, FeedbackEventType.SURFACED));
            reactions.surfaced(check);
        }
    }

    private FeedbackEntry entry(ProactiveCheck check, FeedbackEventType type) {
        return FeedbackEntry.of(
                        check.userId(),
                        FeedbackSubjectType.CHECK,
                        check.id(),
                        type,
                        FeedbackActor.SYSTEM,
                        clock.instant())
                .conversation(check.conversationId())
                .originExecution(check.rootExecutionId())
                .sourceCheck(check.id());
    }
}
