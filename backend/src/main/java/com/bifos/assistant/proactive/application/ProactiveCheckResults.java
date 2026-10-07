package com.bifos.assistant.proactive.application;

import static com.bifos.assistant.proactive.application.ProactiveCheckRun.EMPTY_ANSWER_NOTICE;
import static com.bifos.assistant.proactive.application.ProactiveCheckRun.INVALID_RESULT_NOTICE;
import static com.bifos.assistant.proactive.application.ProactiveCheckRun.NOTHING_NEW_NOTICE;

import com.bifos.assistant.chat.application.model.CheckAnswer;
import com.bifos.assistant.proactive.application.model.AnnouncedKey;
import com.bifos.assistant.proactive.application.model.CheckResultBlock;
import com.bifos.assistant.proactive.application.model.CheckResultRead;
import com.bifos.assistant.proactive.application.model.JudgedFinding;
import com.bifos.assistant.proactive.application.model.JudgedProblem;
import com.bifos.assistant.proactive.domain.CheckReport;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.type.CheckInvalidReason;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/** 결과 블록을 판정하고 답을 만든 뒤, 답 저장 후 발견과 문제 후보를 저장한다. */
@Slf4j
class ProactiveCheckResults {
    private final CurrentUser owner;
    private final ProactiveCheck check;
    private final ProactiveCheckRun.Deps deps;

    private volatile CheckOutcome outcome;
    /** 결과 블록을 읽지 못한 까닭. {@link #outcome} 이 {@code INVALID_RESULT} 일 때만 있다. */
    private volatile CheckInvalidReason invalidReason;

    private volatile int newFindings;
    private volatile int referenceFindings;
    /** 검사한 발견. 답 메시지를 저장한 뒤 {@link #saveFindings} 가 저장한다. 블록에 발견이 없으면 비어 있다. */
    private volatile List<ProactiveCheckFinding> pendingFindings = List.of();
    /** 검사한 문제 후보. 발견과 함께 {@link #saveFindings} 가 저장한다. 발견이 없으면 비어 있다. */
    private volatile List<ProactiveCheckProblem> pendingProblems = List.of();
    /** 결과 블록 v2를 검사해 만든 보고다. */
    private volatile CheckReport pendingReport;

    ProactiveCheckResults(CurrentUser owner, ProactiveCheck check, ProactiveCheckRun.Deps deps) {
        this.owner = owner;
        this.check = check;
        this.deps = deps;
    }

    /**
     * 결과 블록을 읽고 발견을 검사해 대화에 남길 글을 정한다. 발견은 들고 있다가 답 메시지를 저장한 뒤 {@link #saveFindings} 가
     * 저장하고, 결과와 셈은 {@link ProactiveCheckRun#record} 가 적는다. 답을 저장하지 못했는데 발견이 남으면 사용자가 보지 못한 발견이 다음 살펴보기에서
     * 이미 알린 것으로 내려가기 때문이다.
     * NOTHING_NEW 여도 질문이나 읽지 못한 출처가 있으면 그린다.
     *
     * <p>블록을 읽지 못하면 까닭과 답의 길이만 로그에 남긴다. 답은 개인 맥락을 담을 수 있어 본문을 남기지 않는다.
     */
    CheckAnswer answer(Long executionId, String output) {
        CheckResultRead read = deps.parser().read(output);
        if (read.block() == null) {
            outcome = CheckOutcome.INVALID_RESULT;
            invalidReason = read.invalidReason();
            log.warn(
                    "살펴보기 결과 블록을 읽지 못했다 checkId={} executionId={} reason={} answerLength={}",
                    check.id(),
                    executionId,
                    invalidReason,
                    output == null ? 0 : output.length());
            return new CheckAnswer(
                    invalidReason == CheckInvalidReason.EMPTY_ANSWER ? EMPTY_ANSWER_NOTICE : INVALID_RESULT_NOTICE,
                    true,
                    false);
        }
        CheckResultBlock block = read.block();
        outcome = block.outcome();
        if (block.outcome() == CheckOutcome.NOTHING_NEW
                && block.findings().isEmpty()
                && block.questions().isEmpty()
                && block.sourceFailures().isEmpty()) {
            if (check.trigger() == CheckTrigger.SCHEDULED) {
                return new CheckAnswer("", false, true);
            }
            return new CheckAnswer(NOTHING_NEW_NOTICE, true, false);
        }
        Instant now = deps.clock().instant();
        Set<AnnouncedKey> announced =
                announcedSince(now.minus(deps.properties().current().digestWindow()));
        List<JudgedFinding> judged = block.findings().stream()
                .map(finding -> FindingJudgement.judge(finding, check.startedAt(), now, announced))
                .toList();
        newFindings = (int)
                judged.stream().filter(each -> each.kind() == FindingKind.NEW).count();
        referenceFindings = judged.size() - newFindings;
        pendingFindings = judged.stream()
                .map(each -> ProactiveCheckFinding.of(
                        check.id(),
                        check.conversationId(),
                        each.kind(),
                        each.reason(),
                        Objects.requireNonNullElse(each.finding().area(), ""),
                        each.finding().topicKey(),
                        Objects.requireNonNullElse(each.finding().title(), ""),
                        each.sourceUrl(),
                        each.checkedAt(),
                        now))
                .toList();
        pendingProblems = block.problemCandidates().isEmpty() || judged.isEmpty()
                ? List.of()
                : ProblemJudgement.judge(
                                block.problemCandidates(),
                                judged,
                                acceptedProblemKeysSince(
                                        now.minus(deps.properties().current().digestWindow())),
                                title -> deps.followUps().hasOpenWithTitle(owner.id(), title))
                        .stream()
                        .map(each -> problemRow(each, now))
                        .toList();
        if (check.trigger() == CheckTrigger.SCHEDULED
                && newFindings == 0
                && block.questions().isEmpty()
                && block.sourceFailures().isEmpty()) {
            // 검사를 통과한 새 발견이 없으면 정상 무변화와 같다. 참고로 내린 발견은 셈을 위해 저장하되 답과 보고를 남기지 않는다.
            // 되풀이가 아닌 까닭으로 내린 발견은 결과 형식이나 분야 지침의 문제일 수 있어 까닭만 로그에 남긴다.
            Map<FindingReason, Long> reasons = judged.stream()
                    .filter(each -> each.reason() != null && each.reason() != FindingReason.REPEATED)
                    .collect(Collectors.groupingBy(JudgedFinding::reason, Collectors.counting()));
            if (!reasons.isEmpty()) {
                log.info("예약 살펴보기의 발견이 모두 참고로 내려가 침묵했다 checkId={} reasons={}", check.id(), reasons);
            }
            return new CheckAnswer("", false, true);
        }
        pendingReport = deps.reportFactory().create(block, judged, executionId);
        return new CheckAnswer(deps.renderer().render(block, judged, pendingReport), false, false);
    }

    /**
     * 들고 있던 발견과 문제 후보를 저장한다. turn 이 답 메시지를 저장하고 돌아왔을 때만 부른다. 없으면 아무것도 하지 않는다. 사용자가 보지 못한
     * 발견을 근거로 한 후보가 다음 살펴보기에서 중복으로 걸리지 않게 하기 위해 발견과 같은 자리에서 저장한다.
     */
    void saveFindings() {
        // 사용자가 보지 못한 발견이 다음 살펴보기에서 이미 알린 것으로 내려가지 않게, 알리지 않는 살펴보기는 발견을 남기지 않는다.
        List<ProactiveCheckFinding> judged = silent() ? List.of() : pendingFindings;
        if (!judged.isEmpty()) {
            deps.findings().saveAll(judged);
        }
        List<ProactiveCheckProblem> problems = pendingProblems;
        if (!problems.isEmpty()) {
            deps.problems().saveAll(problems);
        }
    }

    /** 검사한 후보 하나를 줄로 만든다. 행동이 없으면 그 칸을 비운다. */
    private ProactiveCheckProblem problemRow(JudgedProblem judged, Instant now) {
        CheckResultBlock.ProblemCandidate candidate = judged.candidate();
        CheckResultBlock.Next action = candidate.proposedAction();
        return ProactiveCheckProblem.of(
                check.id(),
                check.conversationId(),
                judged.status(),
                judged.reason(),
                judged.problemKey(),
                candidate.problem(),
                candidate.relatedGoal(),
                action == null ? null : action.type(),
                action == null ? null : action.text(),
                candidate.confidence(),
                candidate.expectedBenefit(),
                candidate.sideEffect(),
                candidate.risk(),
                candidate.changeSinceLast(),
                judged.evidence(),
                judged.evidenceCheckedAt(),
                now);
    }

    /** 그 시각 뒤에 받아들인 문제 후보의 정규화한 문제 키다. */
    private Set<String> acceptedProblemKeysSince(Instant after) {
        return deps
                .problems()
                .findByConversationIdAndStatusAndCreatedAtAfter(check.conversationId(), ProblemStatus.ACCEPTED, after)
                .stream()
                .map(ProactiveCheckProblem::problemKey)
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toSet());
    }

    /** 이미 알린 주제 키와 원문 주소. 주제 키가 빈 발견은 되풀이 판정을 하지 않으므로 넣지 않는다. */
    private Set<AnnouncedKey> announcedSince(Instant after) {
        return deps
                .findings()
                .findByConversationIdAndKindAndCreatedAtAfter(check.conversationId(), FindingKind.NEW, after)
                .stream()
                .filter(finding ->
                        finding.topicKey() != null && !finding.topicKey().isBlank())
                .map(finding -> new AnnouncedKey(finding.topicKey(), finding.sourceUrl()))
                .collect(Collectors.toSet());
    }

    /**
     * 자동 실행으로 시작해 답, 보고, 발견, 알림 줄을 남기지 않는 살펴보기인가. 문제 후보만 남겨 다시 가치 평가와 행동 정책을 거치게
     * 한다.
     */
    boolean silent() {
        return check.trigger() == CheckTrigger.AUTONOMY;
    }

    CheckOutcome outcome() {
        return outcome;
    }

    CheckInvalidReason invalidReason() {
        return invalidReason;
    }

    CheckReport pendingReport() {
        return pendingReport;
    }

    int newFindings() {
        return newFindings;
    }

    int referenceFindings() {
        return referenceFindings;
    }
}
