package com.bifos.assistant.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.application.model.FeedbackLabel;
import com.bifos.assistant.feedback.application.model.SubjectLabel;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사용자 반응 fixture 로 반응 읽기 규칙을 본다. 무응답과 거절이 오래 가는 싫어함이 되지 않는지가 중심이다. */
class FeedbackLabelerTest {

    private static final Instant T0 = Instant.parse("2026-10-07T00:00:00Z");

    @Test
    @DisplayName("보인 뒤 바로 받아들인 제안은 ACCEPTED 이고 지금 원한 표본이다")
    void labelsImmediateAcceptance() {
        SubjectLabel label = FeedbackLabeler.label(
                FeedbackSubjectType.FOLLOW_UP,
                events(
                        FeedbackActor.AGENT, FeedbackEventType.SURFACED,
                        FeedbackActor.USER, FeedbackEventType.ACCEPTED));

        assertThat(label.label()).isEqualTo(FeedbackLabel.ACCEPTED);
        assertThat(label.wantsNow()).isTrue();
        assertThat(label.persistentPreference()).isFalse();
    }

    @Test
    @DisplayName("거절은 그 제안 하나의 DECLINED 이고 오래 가는 선호가 아니다")
    void rejectionIsOneTime() {
        SubjectLabel label = FeedbackLabeler.label(
                FeedbackSubjectType.FOLLOW_UP,
                events(
                        FeedbackActor.AGENT, FeedbackEventType.SURFACED,
                        FeedbackActor.USER, FeedbackEventType.REJECTED));

        assertThat(label.label()).isEqualTo(FeedbackLabel.DECLINED);
        assertThat(label.wantsNow()).isFalse();
        assertThat(label.persistentPreference()).isFalse();
    }

    @Test
    @DisplayName("보였지만 반응하지 않은 제안은 NO_RESPONSE 이고 표본에서 빠진다")
    void silenceIsNotDislike() {
        SubjectLabel label = FeedbackLabeler.label(
                FeedbackSubjectType.MEMORY, events(FeedbackActor.AGENT, FeedbackEventType.SURFACED));

        assertThat(label.label()).isEqualTo(FeedbackLabel.NO_RESPONSE);
        assertThat(label.wantsNow()).isNull();
        assertThat(label.persistentPreference()).isFalse();
    }

    @Test
    @DisplayName("미루기만 한 제안은 DEFERRED 이고 지금은 아니라는 표본이지만 싫어함은 아니다")
    void postponeIsNotNow() {
        SubjectLabel label = FeedbackLabeler.label(
                FeedbackSubjectType.CONNECTOR_ACTION,
                events(
                        FeedbackActor.AGENT, FeedbackEventType.SURFACED,
                        FeedbackActor.USER, FeedbackEventType.POSTPONED));

        assertThat(label.label()).isEqualTo(FeedbackLabel.DEFERRED);
        assertThat(label.wantsNow()).isFalse();
        assertThat(label.persistentPreference()).isFalse();
    }

    @Test
    @DisplayName("고친 뒤 받아들이고 끝낸 할 일은 ACCEPTED 와 고침, 성공한 결과를 함께 남긴다")
    void editedAcceptedAndDone() {
        SubjectLabel label = FeedbackLabeler.label(
                FeedbackSubjectType.FOLLOW_UP,
                events(
                        FeedbackActor.AGENT, FeedbackEventType.SURFACED,
                        FeedbackActor.USER, FeedbackEventType.EDITED,
                        FeedbackActor.USER, FeedbackEventType.ACCEPTED,
                        FeedbackActor.USER, FeedbackEventType.EXECUTION_SUCCEEDED));

        assertThat(label.label()).isEqualTo(FeedbackLabel.ACCEPTED);
        assertThat(label.edited()).isTrue();
        assertThat(label.outcome()).isEqualTo(FeedbackEventType.EXECUTION_SUCCEEDED);
    }

    @Test
    @DisplayName("받아들인 뒤 그만둔 할 일은 첫 반응인 ACCEPTED 로 읽고 나중의 그만둠이 첫 반응을 덮지 않는다")
    void laterDropKeepsFirstResponse() {
        SubjectLabel label = FeedbackLabeler.label(
                FeedbackSubjectType.FOLLOW_UP,
                events(
                        FeedbackActor.AGENT, FeedbackEventType.SURFACED,
                        FeedbackActor.USER, FeedbackEventType.ACCEPTED,
                        FeedbackActor.USER, FeedbackEventType.DISMISSED));

        assertThat(label.label()).isEqualTo(FeedbackLabel.ACCEPTED);
        assertThat(label.wantsNow()).isTrue();
    }

    @Test
    @DisplayName("승인했지만 실행이 실패한 승인 줄은 ACCEPTED 와 실패한 결과다")
    void approvedButFailed() {
        SubjectLabel label = FeedbackLabeler.label(
                FeedbackSubjectType.CONNECTOR_ACTION,
                events(
                        FeedbackActor.AGENT, FeedbackEventType.SURFACED,
                        FeedbackActor.USER, FeedbackEventType.APPROVED,
                        FeedbackActor.SYSTEM, FeedbackEventType.EXECUTION_FAILED));

        assertThat(label.label()).isEqualTo(FeedbackLabel.ACCEPTED);
        assertThat(label.outcome()).isEqualTo(FeedbackEventType.EXECUTION_FAILED);
    }

    @Test
    @DisplayName("받아들인 Memory 제안만 오래 가는 선호의 근거다")
    void onlyAcceptedMemoryIsPersistent() {
        SubjectLabel accepted = FeedbackLabeler.label(
                FeedbackSubjectType.MEMORY,
                events(
                        FeedbackActor.AGENT, FeedbackEventType.SURFACED,
                        FeedbackActor.USER, FeedbackEventType.ACCEPTED));
        SubjectLabel rejected = FeedbackLabeler.label(
                FeedbackSubjectType.MEMORY,
                events(
                        FeedbackActor.AGENT, FeedbackEventType.SURFACED,
                        FeedbackActor.USER, FeedbackEventType.REJECTED));

        assertThat(accepted.persistentPreference()).isTrue();
        assertThat(rejected.persistentPreference()).isFalse();
    }

    @Test
    @DisplayName("사용자에게 보인 적 없는 자동 실행의 결과는 NOT_SURFACED 이고 표본이 아니다")
    void autonomousOutcomeIsNotASample() {
        SubjectLabel label = FeedbackLabeler.label(
                FeedbackSubjectType.CHECK, events(FeedbackActor.SYSTEM, FeedbackEventType.EXECUTION_SUCCEEDED));

        assertThat(label.label()).isEqualTo(FeedbackLabel.NOT_SURFACED);
        assertThat(label.wantsNow()).isNull();
        assertThat(label.outcome()).isEqualTo(FeedbackEventType.EXECUTION_SUCCEEDED);
    }

    @Test
    @DisplayName("에이전트나 시스템의 사건은 사용자 반응으로 읽지 않는다")
    void ignoresNonUserResponses() {
        SubjectLabel label = FeedbackLabeler.label(
                FeedbackSubjectType.FOLLOW_UP,
                events(
                        FeedbackActor.AGENT, FeedbackEventType.SURFACED,
                        FeedbackActor.SYSTEM, FeedbackEventType.REJECTED));

        assertThat(label.label()).isEqualTo(FeedbackLabel.NO_RESPONSE);
    }

    /** 주체와 종류를 번갈아 받아 1분 간격의 사건을 만든다. */
    private static List<FeedbackEvent> events(Object... actorAndType) {
        List<FeedbackEvent> events = new ArrayList<>();
        for (int i = 0; i < actorAndType.length; i += 2) {
            events.add(FeedbackEvent.of(
                    1L,
                    FeedbackSubjectType.FOLLOW_UP,
                    "follow_up:fixture",
                    (FeedbackEventType) actorAndType[i + 1],
                    (FeedbackActor) actorAndType[i],
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    T0.plusSeconds(60L * i)));
        }
        return events;
    }
}
