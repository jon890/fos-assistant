package com.bifos.assistant.proactive.check.application;

import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.dto.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckStatus;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 끝난 살펴보기의 판단 피드백 사건을 남긴다(ADR-20261007 decision-feedback).
 *
 * <p>사용자가 연 살펴보기와 매일 깨우기는 검사한 보고를 남겼을 때만 {@code SURFACED} 다. 알릴 것이 없어 보고를 남기지 않은 침묵은 사건이
 * 아니다.
 */
@Component
@RequiredArgsConstructor
public class CheckFeedback {

    private final DecisionFeedbackRecorder feedback;
    private final ProactiveCheckRepository checks;
    private final Clock clock;


    /**
     * 줄을 적은 뒤에 부른다. 저장된 줄을 다시 읽어 판정한다. 끝난 상태를 저장하지 못했으면 아직 도는 줄이라 남기지 않고, 기동 정리가 닫을 때
     * 남긴다.
     */
    public void ended(ProactiveCheck finished) {
        ProactiveCheck check = finished.id() == null ? null : checks.findById(finished.id()).orElse(null);
        if (check == null || check.status() == CheckStatus.RUNNING) {
            return;
        }
        if (check.report() != null) {
            feedback.record(entry(check, FeedbackEventType.SURFACED));
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
            .originExecution(check.rootExecutionId());
    }
}
