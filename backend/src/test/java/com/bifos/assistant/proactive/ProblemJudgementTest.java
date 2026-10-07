package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.proactive.application.FindingJudgement;
import com.bifos.assistant.proactive.application.ProblemJudgement;
import com.bifos.assistant.proactive.application.model.AnnouncedKey;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.Finding;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.Next;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.ProblemCandidate;
import com.bifos.assistant.proactive.application.model.JudgedFinding;
import com.bifos.assistant.proactive.application.model.JudgedProblem;
import com.bifos.assistant.proactive.domain.ProblemEvidence;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import com.bifos.assistant.proactive.domain.type.ProblemDropReason;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 문제 후보 검사의 순서와 조건을 커리어 분야의 합성 fixture 로 고정한다(ADR-093).
 *
 * <p>fixture 는 급한 문제, 나중에 중요한 문제, 목표 없는 관찰, 중복, 할 일 없음이다. 발견은 실제 검사({@link FindingJudgement})를
 * 거쳐 만든다. 회사와 주소는 모두 가상이다.
 */
class ProblemJudgementTest {

    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-07T00:03:00Z");
    private static final String CHECKED_AT = "2026-10-07T09:01:00+09:00";
    private static final String EARLIER_CHECKED_AT = "2026-10-07T09:00:30+09:00";

    private static final String POSITION_TOPIC = "position:example-corp-backend";
    private static final String POSITION_URL = "https://jobs.example.com/example-corp/backend";
    private static final String STUDY_TOPIC = "study:kafka-exactly-once";
    private static final String STUDY_URL = "https://docs.example.com/kafka/exactly-once";
    private static final String CLOSED_TOPIC = "position:example-closed";
    private static final String UNSOURCED_TOPIC = "trend:unsourced";

    private static final Predicate<String> NO_OPEN_FOLLOW_UP = title -> false;

    private static Finding finding(String area, String topicKey, String sourceUrl, String checkedAt, String freshness) {
        return new Finding(
                area,
                topicKey,
                "제목 " + topicKey,
                sourceUrl,
                checkedAt,
                null,
                freshness,
                "지금 하는 일과 닿아 있다",
                List.of("원문에서 확인한 사실"),
                List.of(),
                List.of(),
                new Next("ACTION", "원문을 읽는다"),
                null);
    }

    /** 공고 마감이 다가온 「새로 알릴 것」, 이미 알린 공부 자료, 마감 공고, 원문 없는 동향의 네 발견이다. */
    private static List<JudgedFinding> careerFindings() {
        Set<AnnouncedKey> announced = Set.of(new AnnouncedKey(STUDY_TOPIC, STUDY_URL));
        return List.of(
                        finding("position", POSITION_TOPIC, POSITION_URL, CHECKED_AT, "CURRENT"),
                        finding("study", STUDY_TOPIC, STUDY_URL, EARLIER_CHECKED_AT, "CURRENT"),
                        finding("position", CLOSED_TOPIC, "https://jobs.example.com/closed", CHECKED_AT, "CLOSED"),
                        finding("trend", UNSOURCED_TOPIC, "", CHECKED_AT, "CURRENT"))
                .stream()
                .map(each -> FindingJudgement.judge(each, START, NOW, announced))
                .toList();
    }

    /** 공고 마감 전에 지원 여부를 정해야 하는 급한 문제다. */
    private static ProblemCandidate urgent() {
        return new ProblemCandidate(
                "Position:Deadline:Example-Corp ",
                "관심 회사의 백엔드 공고가 이틀 뒤 마감되는데 지원 여부를 정하지 않았다",
                "백엔드 AI 포지션으로 이직을 준비한다",
                List.of(POSITION_TOPIC),
                new Next("QUESTION", "이번 주 안에 지원할지 정할까요"),
                "HIGH",
                "마감 전에 지원 기회를 놓치지 않는다",
                "NONE",
                null,
                null);
    }

    /** 지금 급하지는 않지만 목표에 중요한 공부 문제다. 근거는 이미 알린 자료다. */
    private static ProblemCandidate importantLater() {
        return new ProblemCandidate(
                "study:exactly-once-gap",
                "정확히 한 번 처리를 면접에서 설명할 근거가 부족하다",
                "다음 분기 면접 준비",
                List.of(STUDY_TOPIC),
                new Next("ACTION", "정확히 한 번 처리 예제를 한 번 돌려 보기"),
                "MEDIUM",
                "면접에서 설계 근거를 말할 수 있다",
                "INTERNAL",
                null,
                null);
    }

    private static ProblemCandidate withGoal(ProblemCandidate base, String relatedGoal) {
        return new ProblemCandidate(
                base.problemKey(),
                base.problem(),
                relatedGoal,
                base.evidence(),
                base.proposedAction(),
                base.confidence(),
                base.expectedBenefit(),
                base.sideEffect(),
                base.risk(),
                base.changeSinceLast());
    }

    private static ProblemCandidate withEvidence(ProblemCandidate base, List<String> evidence) {
        return new ProblemCandidate(
                base.problemKey(),
                base.problem(),
                base.relatedGoal(),
                evidence,
                base.proposedAction(),
                base.confidence(),
                base.expectedBenefit(),
                base.sideEffect(),
                base.risk(),
                base.changeSinceLast());
    }

    private static ProblemCandidate withChange(ProblemCandidate base, String changeSinceLast) {
        return new ProblemCandidate(
                base.problemKey(),
                base.problem(),
                base.relatedGoal(),
                base.evidence(),
                base.proposedAction(),
                base.confidence(),
                base.expectedBenefit(),
                base.sideEffect(),
                base.risk(),
                changeSinceLast);
    }

    private static ProblemCandidate withConfidence(ProblemCandidate base, String confidence) {
        return new ProblemCandidate(
                base.problemKey(),
                base.problem(),
                base.relatedGoal(),
                base.evidence(),
                base.proposedAction(),
                confidence,
                base.expectedBenefit(),
                base.sideEffect(),
                base.risk(),
                base.changeSinceLast());
    }

    private static JudgedProblem only(List<ProblemCandidate> candidates, Set<String> accepted) {
        List<JudgedProblem> judged = ProblemJudgement.judge(candidates, careerFindings(), accepted, NO_OPEN_FOLLOW_UP);
        assertThat(judged).hasSize(1);
        return judged.getFirst();
    }

    @Test
    @DisplayName("급한 문제: 새로 알린 공고를 근거로 한 후보는 받아들이고 정규화한 키와 근거 참조와 확인 시각을 남긴다")
    void acceptsUrgentCandidateWithEvidenceReference() {
        JudgedProblem judged = only(List.of(urgent()), Set.of());

        assertThat(judged.status()).isEqualTo(ProblemStatus.ACCEPTED);
        assertThat(judged.reason()).isNull();
        assertThat(judged.problemKey()).isEqualTo("position:deadline:example-corp");
        assertThat(judged.evidence())
                .containsExactly(new ProblemEvidence(POSITION_TOPIC, POSITION_URL, Instant.parse(CHECKED_AT)));
        assertThat(judged.evidenceCheckedAt()).isEqualTo(Instant.parse(CHECKED_AT));
    }

    @Test
    @DisplayName("나중에 중요한 문제: 이미 알린 발견도 근거가 되고, 근거가 여럿이면 가장 이른 확인 시각을 신선도로 쓴다")
    void acceptsImportantLaterCandidateOnRepeatedFinding() {
        assertThat(careerFindings().get(1).reason()).isEqualTo(FindingReason.REPEATED);
        JudgedProblem judged = only(
                List.of(withEvidence(importantLater(), List.of(STUDY_TOPIC, POSITION_TOPIC, STUDY_TOPIC))), Set.of());

        assertThat(judged.status()).isEqualTo(ProblemStatus.ACCEPTED);
        assertThat(judged.evidence())
                .extracting(ProblemEvidence::topicKey)
                .containsExactly(STUDY_TOPIC, POSITION_TOPIC);
        assertThat(judged.evidenceCheckedAt()).isEqualTo(Instant.parse(EARLIER_CHECKED_AT));
    }

    @Test
    @DisplayName("목표 없는 관찰: 새 자료가 나왔다는 사실만으로 낸 후보는 NO_GOAL 로 버린다")
    void dropsLowValueObservationWithoutGoal() {
        JudgedProblem judged = only(List.of(withGoal(urgent(), " ")), Set.of());

        assertThat(judged.status()).isEqualTo(ProblemStatus.DROPPED);
        assertThat(judged.reason()).isEqualTo(ProblemDropReason.NO_GOAL);
    }

    @Test
    @DisplayName("근거: 마감 공고, 원문 없는 발견, 블록에 없는 주제 키만 가리키면 NO_EVIDENCE 다")
    void dropsCandidateWithoutUsableEvidence() {
        for (List<String> evidence : List.of(
                List.of(CLOSED_TOPIC), List.of(UNSOURCED_TOPIC), List.of("position:not-in-block"), List.<String>of())) {
            JudgedProblem judged = only(List.of(withEvidence(urgent(), evidence)), Set.of());

            assertThat(judged.reason()).as("근거 %s", evidence).isEqualTo(ProblemDropReason.NO_EVIDENCE);
            assertThat(judged.evidence()).isEmpty();
            assertThat(judged.evidenceCheckedAt()).isNull();
        }
    }

    @Test
    @DisplayName("중복: 최근에 받아들인 키는 버리고, 달라진 점을 적으면 다시 받아들인다")
    void dropsDuplicateOfRecentlyAcceptedKeyUnlessChanged() {
        Set<String> accepted = Set.of("position:deadline:example-corp");

        assertThat(only(List.of(urgent()), accepted).reason()).isEqualTo(ProblemDropReason.DUPLICATE);
        assertThat(only(List.of(withChange(urgent(), "마감이 이틀 앞으로 다가왔다")), accepted)
                        .status())
                .isEqualTo(ProblemStatus.ACCEPTED);
    }

    @Test
    @DisplayName("중복: 같은 블록에서 같은 키를 두 번 내면 뒤의 것은 달라진 점이 있어도 버린다")
    void dropsDuplicateWithinBlock() {
        List<JudgedProblem> judged = ProblemJudgement.judge(
                List.of(urgent(), withChange(urgent(), "다른 표현")), careerFindings(), Set.of(), NO_OPEN_FOLLOW_UP);

        assertThat(judged)
                .extracting(JudgedProblem::status)
                .containsExactly(ProblemStatus.ACCEPTED, ProblemStatus.DROPPED);
        assertThat(judged.getLast().reason()).isEqualTo(ProblemDropReason.DUPLICATE);
    }

    @Test
    @DisplayName("중복: 버린 후보의 키는 같은 블록의 뒤 후보를 막지 않는다")
    void droppedCandidateDoesNotBlockLaterOne() {
        List<JudgedProblem> judged = ProblemJudgement.judge(
                List.of(withGoal(urgent(), null), urgent()), careerFindings(), Set.of(), NO_OPEN_FOLLOW_UP);

        assertThat(judged)
                .extracting(JudgedProblem::status)
                .containsExactly(ProblemStatus.DROPPED, ProblemStatus.ACCEPTED);
    }

    @Test
    @DisplayName("이미 챙기는 일: 행동 글이 열린 할 일의 제목과 같으면 EXISTING_FOLLOW_UP 이다")
    void dropsCandidateMatchingOpenFollowUp() {
        Predicate<String> open = title -> title.equals("정확히 한 번 처리 예제를 한 번 돌려 보기");

        List<JudgedProblem> judged =
                ProblemJudgement.judge(List.of(importantLater(), urgent()), careerFindings(), Set.of(), open);

        assertThat(judged)
                .extracting(JudgedProblem::reason)
                .containsExactly(ProblemDropReason.EXISTING_FOLLOW_UP, null);
    }

    @Test
    @DisplayName("계약: 정해진 값 밖의 확신이나 빈 문제 키는 INCOMPLETE 이고 다른 검사보다 먼저 걸린다")
    void dropsIncompleteCandidateFirst() {
        assertThat(only(List.of(withGoal(withConfidence(urgent(), "VERY_HIGH"), null)), Set.of())
                        .reason())
                .isEqualTo(ProblemDropReason.INCOMPLETE);
        ProblemCandidate blankKey = new ProblemCandidate(
                " ",
                "문제",
                "목표",
                List.of(POSITION_TOPIC),
                new Next("ACTION", "행동"),
                "LOW",
                "효과",
                "EXTERNAL",
                "되돌리기 어렵다",
                null);
        JudgedProblem judged = only(List.of(blankKey), Set.of());
        assertThat(judged.reason()).isEqualTo(ProblemDropReason.INCOMPLETE);
        assertThat(judged.problemKey()).isEmpty();
    }

    @Test
    @DisplayName("계약: 행동이 없거나 영향 힌트가 정해진 값 밖이면 INCOMPLETE 다")
    void dropsCandidateWithoutActionOrKnownSideEffect() {
        ProblemCandidate noAction = new ProblemCandidate(
                "study:no-action", "문제", "목표", List.of(POSITION_TOPIC), null, "LOW", "효과", "NONE", null, null);
        ProblemCandidate unknownSideEffect = new ProblemCandidate(
                "study:side-effect",
                "문제",
                "목표",
                List.of(POSITION_TOPIC),
                new Next("ACTION", "행동"),
                "LOW",
                "효과",
                "SEND_MAIL",
                null,
                null);

        List<JudgedProblem> judged = ProblemJudgement.judge(
                List.of(noAction, unknownSideEffect), careerFindings(), Set.of(), NO_OPEN_FOLLOW_UP);

        assertThat(judged)
                .extracting(JudgedProblem::reason)
                .containsExactly(ProblemDropReason.INCOMPLETE, ProblemDropReason.INCOMPLETE);
    }

    @Test
    @DisplayName("근거: 주제 키의 앞뒤 공백은 무시하고 견준다")
    void matchesEvidenceIgnoringSurroundingSpaces() {
        JudgedProblem judged = only(List.of(withEvidence(urgent(), List.of("  " + POSITION_TOPIC + " "))), Set.of());

        assertThat(judged.status()).isEqualTo(ProblemStatus.ACCEPTED);
        assertThat(judged.evidence()).extracting(ProblemEvidence::topicKey).containsExactly(POSITION_TOPIC);
    }

    @Test
    @DisplayName("할 일 없음: 발견이 없는 블록은 후보를 검사하지도 남기지도 않는다")
    void returnsNothingWhenBlockHasNoFindings() {
        assertThat(ProblemJudgement.judge(List.of(urgent()), List.of(), Set.of(), NO_OPEN_FOLLOW_UP))
                .isEmpty();
        assertThat(ProblemJudgement.judge(List.of(), careerFindings(), Set.of(), NO_OPEN_FOLLOW_UP))
                .isEmpty();
    }
}
