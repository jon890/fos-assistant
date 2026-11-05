package com.bifos.assistant.proactive.check.application;

import static com.bifos.assistant.proactive.check.application.ProactiveCheckRun.EMPTY_ANSWER_NOTICE;
import static com.bifos.assistant.proactive.check.application.ProactiveCheckRun.INVALID_RESULT_NOTICE;
import static com.bifos.assistant.proactive.check.application.ProactiveCheckRun.NOTHING_NEW_NOTICE;

import com.bifos.assistant.chat.turn.application.dto.CheckAnswer;
import com.bifos.assistant.followup.application.dto.SuggestedItem;
import com.bifos.assistant.proactive.check.application.dto.AnnouncedKey;
import com.bifos.assistant.proactive.check.application.dto.CheckResultBlock;
import com.bifos.assistant.proactive.check.application.dto.CheckResultRead;
import com.bifos.assistant.proactive.check.application.dto.EventBatch;
import com.bifos.assistant.proactive.check.application.dto.JudgedFinding;
import com.bifos.assistant.proactive.check.application.dto.JudgedProblem;
import com.bifos.assistant.proactive.check.domain.CheckReport;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckInvalidReason;
import com.bifos.assistant.proactive.check.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.check.domain.type.FindingKind;
import com.bifos.assistant.proactive.check.domain.type.FindingReason;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 결과 블록을 판정하고 답과 보고를 만든 뒤, 답 저장 후 제안을 할 일 줄로 저장한다. 발견과 문제 후보는 답과 보고를 그리는 데만 쓰고 따로
 * 저장하지 않는다. 다음 살펴보기의 되풀이 판정 재료는 보고({@link CheckReport})에 함께 담긴다.
 */
@Slf4j
class ProactiveCheckResults {
    private final CurrentUser owner;
    private final ProactiveCheck check;
    private final EventBatch event;
    private final ProactiveCheckRun.Deps deps;

    private volatile CheckOutcome outcome;
    /** 결과 블록을 읽지 못한 까닭. {@link #outcome} 이 {@code INVALID_RESULT} 일 때만 있다. */
    private volatile CheckInvalidReason invalidReason;

    /** 검사해 만든 보고다. 보고를 남기지 않는 살펴보기면 비어 있다. */
    private volatile CheckReport pendingReport;
    /** 결과 블록의 제안 항목. 답 메시지를 저장한 뒤 {@link #saveSuggestions} 가 할 일 줄로 저장한다. */
    private volatile List<SuggestedItem> pendingSuggestions = List.of();
    /** 제안 항목을 낸 turn 의 실행 번호다. */
    private volatile Long suggestionExecutionId;
    /** 사건 실행이 알릴 항목 수다. 다른 계기와 침묵한 실행은 0 이다. */
    private volatile int notifyCount;


    /** @param event 사건 실행이 살펴본 새 항목. 다른 계기는 null */
    ProactiveCheckResults(CurrentUser owner, ProactiveCheck check, EventBatch event, ProactiveCheckRun.Deps deps) {
        this.owner = owner;
        this.check = check;
        this.event = event;
        this.deps = deps;
    }


    /**
     * 「새로 알릴 것」 으로 남은 발견 가운데 {@code topicKey} 가 입력의 식별자인 것의 서로 다른 수다(ADR-20261105 / event-watch). 입력 밖
     * 식별자의 발견은 {@link FindingJudgement#withinEvent} 가 이미 「참고」 로 내려 답에 보이는 목록과 알림의 수가 같다. 같은 항목을 두 칸에
     * 쓰면 하나로 센다.
     */
    static int notifiable(List<JudgedFinding> judged, EventBatch event) {
        Set<String> ids = Set.copyOf(event.ids());
        return Math.toIntExact(judged.stream()
            .filter(each -> each.kind() == FindingKind.NEW)
            .map(each -> each.finding().topicKey())
            .filter(ids::contains)
            .distinct()
            .count());
    }


    /**
     * 결과 블록을 읽고 발견과 문제 후보를 검사해 대화에 남길 글과 보고를 정한다. 보고와 결과는 {@link ProactiveCheckRun#record} 가 줄에
     * 적는다. turn 이 답을 저장하지 못하고 예외로 끝나면 보고도 남지 않아, 사용자가 보지 못한 발견이 다음 살펴보기에서 이미 알린 것으로 내려가지
     * 않는다. NOTHING_NEW 여도 질문이나 읽지 못한 출처가 있으면 그린다.
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
        suggestionExecutionId = executionId;
        pendingSuggestions = block.suggestions().stream()
            .map(each -> new SuggestedItem(each.id(), each.title(), each.detailJson()))
            .toList();
        if (block.outcome() == CheckOutcome.NOTHING_NEW
            && block.findings().isEmpty()
            && block.questions().isEmpty()
            && block.sourceFailures().isEmpty()) {
            if (check.trigger().silentWhenEmpty()) {
                return new CheckAnswer("", false, true);
            }
            return new CheckAnswer(NOTHING_NEW_NOTICE, true, false);
        }
        Instant now = deps.clock().instant();
        Announced announced = announcedSince(now.minus(deps.properties().current().digestWindow()));
        List<JudgedFinding> judged = judge(block, now, announced.keys());
        long newFindings = judged.stream().filter(each -> each.kind() == FindingKind.NEW).count();
        List<JudgedProblem> problems = block.problemCandidates().isEmpty() || judged.isEmpty()
            ? List.of()
            : ProblemJudgement.judge(
                block.problemCandidates(),
                judged,
                announced.acceptedProblemKeys(),
                title -> deps.followUps().hasOpenWithTitle(owner.id(), title));
        if (check.trigger().silentWhenEmpty()
            && newFindings == 0
            && block.questions().isEmpty()
            && block.sourceFailures().isEmpty()) {
            // 검사를 통과한 새 발견이 없으면 정상 무변화와 같다. 답과 보고를 남기지 않아 다음 깨우기가 읽지 않은 보고로 막히지 않는다.
            // 되풀이가 아닌 까닭으로 내린 발견은 결과 형식이나 분야 지침의 문제일 수 있어 까닭만 로그에 남긴다.
            Map<FindingReason, Long> reasons = judged.stream()
                .filter(each -> each.reason() != null && each.reason() != FindingReason.REPEATED)
                .collect(Collectors.groupingBy(JudgedFinding::reason, Collectors.counting()));
            if (!reasons.isEmpty()) {
                log.info("예약 살펴보기의 발견이 모두 참고로 내려가 침묵했다 checkId={} reasons={}", check.id(), reasons);
            }
            return new CheckAnswer("", false, true);
        }
        notifyCount = event == null ? 0 : notifiable(judged, event);
        pendingReport = deps.reportFactory().create(block, judged, problems, executionId);
        return new CheckAnswer(deps.renderer().render(block, judged, pendingReport), false, false);
    }


    /** 발견마다 판정한다. 사건 실행이면 입력 밖 식별자의 발견을 「참고」 로 내린다. */
    private List<JudgedFinding> judge(CheckResultBlock block, Instant now, Set<AnnouncedKey> announced) {
        Set<String> eventIds = event == null ? null : Set.copyOf(event.ids());
        return block.findings().stream()
            .map(finding -> FindingJudgement.judge(finding, check.startedAt(), now, announced))
            .map(each -> eventIds == null ? each : FindingJudgement.withinEvent(each, eventIds))
            .toList();
    }


    /**
     * 결과 블록의 제안 항목을 할 일 줄로 저장한다. turn 이 답 메시지를 저장하고 돌아왔을 때만 부른다. 저장 실패가 살펴보기의 끝을 막지 않게
     * 로그만 남긴다.
     */
    void saveSuggestions() {
        List<SuggestedItem> items = pendingSuggestions;
        if (items.isEmpty()) {
            return;
        }
        try {
            deps.followUps().proposeSuggestion(suggestionExecutionId, items);
        } catch (RuntimeException ex) {
            log.warn(
                "살펴보기 제안을 저장하지 못했다 checkId={} executionId={} error={}",
                check.id(),
                suggestionExecutionId,
                ex.getClass().getSimpleName());
        }
    }


    /**
     * 그 시각 뒤에 시작해 보고를 남긴 같은 점검 대화의 살펴보기를 한 번 읽어, 이미 알린 주제 키와 원문 주소, 받아들인 문제 키를 만든다. 보고를
     * 남긴 살펴보기만 사용자에게 알린 것이다.
     */
    private Announced announcedSince(Instant after) {
        List<CheckReport> reports = deps.checks()
            .findByConversationIdAndReportIsNotNullAndStartedAtAfterOrderByIdDesc(check.conversationId(), after)
            .stream()
            .map(ProactiveCheck::report)
            .toList();
        Set<AnnouncedKey> keys = reports.stream()
            .flatMap(report -> report.announced().stream())
            .filter(finding -> finding.topicKey() != null && !finding.topicKey().isBlank())
            .map(finding -> new AnnouncedKey(finding.topicKey(), finding.sourceUrl()))
            .collect(Collectors.toSet());
        Set<String> problemKeys = reports.stream()
            .flatMap(report -> report.acceptedProblemKeys().stream())
            .filter(key -> !key.isEmpty())
            .collect(Collectors.toSet());
        return new Announced(keys, problemKeys);
    }


    /** 되풀이 판정에 쓰는 이미 알린 묶음과 받아들인 문제 키다. */
    private record Announced(Set<AnnouncedKey> keys, Set<String> acceptedProblemKeys) {
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


    int notifyCount() {
        return notifyCount;
    }
}
