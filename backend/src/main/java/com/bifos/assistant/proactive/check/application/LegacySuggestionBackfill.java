package com.bifos.assistant.proactive.check.application;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.dto.LegacySuggestion;
import com.bifos.assistant.followup.application.dto.SuggestionOutcome;
import com.bifos.assistant.proactive.check.application.dto.BackfillReport;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.check.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.check.domain.type.FindingKind;
import com.bifos.assistant.proactive.check.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기동 뒤 한 번, 옛 발견과 문제 후보를 할 일의 {@code PROPOSED} 줄로 옮긴다(ADR-20261101 / proactive-suggestion 의 옮기는 순서 4).
 *
 * <p>옮기는 것은 사용자에게 보였던 「새로 알릴 것」 발견({@code NEW})과 받아들인 문제 후보({@code ACCEPTED})뿐이다. 「참고」 발견과 버린
 * 후보는 사용자에게 제안으로 보인 적이 없어 옮기지 않는다. 옛 줄은 지우지 않는다. 테이블을 지우는 일은 따로 한다.
 *
 * <p>제안 줄의 사용자, 대화, 실행은 옛 줄과 같다. 옛 분류 원문(종류, 까닭, 확신, 부작용 같은 값)은 제안 본문의 {@code legacy} 자유 필드에만
 * 두고 암호화한다. 이 값으로 지금의 실행을 판정하지 않는다. 중복 열쇠는 {@code legacy:<옛 테이블>:<옛 번호>} 라 다시 돌려도 줄이 늘지 않는다.
 * 같은 제목의 열린 할 일이 이미 있으면 새 줄을 만들지 않고, 이미 있는 할 일 줄은 고치지 않는다.
 *
 * <p>발견의 반응 사건은 옮긴 제안 줄이 있을 때만 그 줄의 열쇠로 옮긴다. 줄을 옮기지 못했으면(같은 제목의 열린 할 일이 이미 있어 이 발견이
 * 어느 줄에 해당하는지 정할 수 없는 경우) 반응은 옛 열쇠에 남는다. 추정해 붙이지 않는다. 문제 후보에 대한 반응은 판정({@code
 * autonomy_decision})에 묶여 있고 한 후보에 판정이 여럿일 수 있어 이 작업에서 옮기지 않는다.
 *
 * <p>줄마다 따로 저장해 한 줄의 실패가 나머지를 되돌리지 않는다. 중간에 멈추면 다음 기동이 남은 줄을 이어서 옮긴다. 옛 줄은 번호 순서로
 * 나누어 읽고, 제목과 본문은 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacySuggestionBackfill implements ApplicationRunner {

    /** 한 번에 읽는 옛 줄 수다. */
    static final int BATCH = 200;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProactiveCheckFindingRepository findings;
    private final ProactiveCheckProblemRepository problems;
    private final ProactiveCheckRepository checks;
    private final ConversationRepository conversations;
    private final FollowUpService followUps;
    private final DecisionFeedbackRecorder feedback;


    @Override
    public void run(ApplicationArguments args) {
        try {
            backfill();
        } catch (RuntimeException ex) {
            // 예외 메시지와 스택은 제목이나 본문을 담을 수 있어 클래스 이름만 남긴다. 기동은 막지 않는다.
            log.error("옛 발견과 문제 후보를 제안 줄로 옮기지 못했다 exception={}", ex.getClass().getName());
        }
    }


    /** 옛 발견과 문제 후보를 모두 옮긴다. 다시 불러도 이미 옮긴 줄은 건드리지 않는다. */
    public BackfillReport backfill() {
        BackfillReport report = migrateFindings().plus(migrateProblems());
        if (report.created() + report.existing() + report.skipped() + report.failed() > 0) {
            log.info(
                "옛 발견과 문제 후보를 제안 줄로 옮겼다 created={} existing={} skipped={} failed={} repointed={}",
                report.created(),
                report.existing(),
                report.skipped(),
                report.failed(),
                report.repointed());
        }
        return report;
    }


    private BackfillReport migrateFindings() {
        BackfillReport total = BackfillReport.EMPTY;
        long after = 0;
        while (true) {
            List<ProactiveCheckFinding> batch = findings.findByKindAndIdGreaterThanOrderByIdAsc(FindingKind.NEW, after, PageRequest.ofSize(BATCH));
            if (batch.isEmpty()) {
                return total;
            }
            Map<Long, ProactiveCheck> owners = ownersOf(batch.stream().map(ProactiveCheckFinding::checkId).toList());
            Set<Long> active = activeConversations(batch.stream().map(ProactiveCheckFinding::conversationId).toList());
            for (ProactiveCheckFinding finding : batch) {
                ProactiveCheck check = owners.get(finding.checkId());
                total = total.plus(check == null || !active.contains(finding.conversationId()) ? new BackfillReport(0, 0, 1, 0, 0) : migrateFinding(finding, check));
            }
            after = batch.getLast().id();
        }
    }


    private BackfillReport migrateFinding(ProactiveCheckFinding finding, ProactiveCheck check) {
        String key = LegacySuggestionKeys.finding(finding.id());
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("table", "proactive_check_finding");
        legacy.put("id", finding.id());
        legacy.put("kind", finding.kind().name());
        putIfPresent(legacy, "reason", finding.reason() == null ? null : finding.reason().name());
        putIfPresent(legacy, "area", finding.area());
        putIfPresent(legacy, "topicKey", finding.topicKey());
        putIfPresent(legacy, "sourceUrl", finding.sourceUrl());
        putIfPresent(legacy, "checkedAt", finding.checkedAt() == null ? null : finding.checkedAt().toString());
        BackfillReport moved = migrate(new LegacySuggestion(
            check.userId(),
            finding.conversationId(),
            check.rootExecutionId(),
            finding.title(),
            key,
            detailOf(legacy),
            finding.createdAt()));
        if (moved.failed() > 0) {
            return moved;
        }
        UUID followUpId = followUps.suggestionPublicIds(check.userId(), List.of(key)).get(key);
        if (followUpId == null) {
            return moved;
        }
        // 옮긴 줄이 있으면 반응을 그 줄로 옮긴다. 이전 기동이 줄만 만들고 멈췄어도 이번 기동이 반응을 이어서 옮긴다.
        int repointed = feedback.repointSubject(
            check.userId(),
            FeedbackSubjectType.CHECK_FINDING.key(finding.id()),
            FeedbackSubjectType.FOLLOW_UP,
            FeedbackSubjectType.FOLLOW_UP.key(followUpId));
        return moved.plus(new BackfillReport(0, 0, 0, 0, repointed));
    }


    private BackfillReport migrateProblems() {
        BackfillReport total = BackfillReport.EMPTY;
        long after = 0;
        while (true) {
            List<ProactiveCheckProblem> batch = problems.findByStatusAndIdGreaterThanOrderByIdAsc(ProblemStatus.ACCEPTED, after, PageRequest.ofSize(BATCH));
            if (batch.isEmpty()) {
                return total;
            }
            Map<Long, ProactiveCheck> owners = ownersOf(batch.stream().map(ProactiveCheckProblem::checkId).toList());
            Set<Long> active = activeConversations(batch.stream().map(ProactiveCheckProblem::conversationId).toList());
            for (ProactiveCheckProblem problem : batch) {
                ProactiveCheck check = owners.get(problem.checkId());
                total = total.plus(check == null || !active.contains(problem.conversationId()) ? new BackfillReport(0, 0, 1, 0, 0) : migrateProblem(problem, check));
            }
            after = batch.getLast().id();
        }
    }


    private BackfillReport migrateProblem(ProactiveCheckProblem problem, ProactiveCheck check) {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("table", "proactive_check_problem");
        legacy.put("id", problem.id());
        legacy.put("status", problem.status().name());
        putIfPresent(legacy, "problemKey", problem.problemKey());
        putIfPresent(legacy, "problem", problem.problem());
        putIfPresent(legacy, "relatedGoal", problem.relatedGoal());
        putIfPresent(legacy, "actionType", problem.actionType());
        putIfPresent(legacy, "actionText", problem.actionText());
        putIfPresent(legacy, "confidence", problem.confidence());
        putIfPresent(legacy, "expectedBenefit", problem.expectedBenefit());
        putIfPresent(legacy, "sideEffect", problem.sideEffect());
        putIfPresent(legacy, "risk", problem.risk());
        putIfPresent(legacy, "changeSinceLast", problem.changeSinceLast());
        if (!problem.evidence().isEmpty()) {
            legacy.put("evidence", problem.evidence());
        }
        putIfPresent(
            legacy,
            "evidenceCheckedAt",
            problem.evidenceCheckedAt() == null ? null : problem.evidenceCheckedAt().toString());
        // 할 일 제목은 사용자가 할 행동이다. 행동 글이 없으면 문제 글을 쓴다.
        String title = problem.actionText() == null || problem.actionText().isBlank()
            ? problem.problem()
            : problem.actionText();
        return migrate(new LegacySuggestion(
            check.userId(),
            problem.conversationId(),
            check.rootExecutionId(),
            title,
            LegacySuggestionKeys.problem(problem.id()),
            detailOf(legacy),
            problem.createdAt()));
    }


    /** 줄 하나를 옮긴다. 저장하지 못하면 세어 두고 다음 줄로 간다. */
    private BackfillReport migrate(LegacySuggestion item) {
        SuggestionOutcome outcome;
        try {
            outcome = followUps.migrateLegacySuggestion(item);
        } catch (RuntimeException ex) {
            log.warn("옛 줄을 제안 줄로 옮기지 못했다 userId={} key={} exception={}",
                item.userId(), item.dedupeKey(), ex.getClass().getName());
            return new BackfillReport(0, 0, 0, 1, 0);
        }
        if (outcome == SuggestionOutcome.CREATED) {
            return new BackfillReport(1, 0, 0, 0, 0);
        }
        // DUPLICATE 는 이미 옮긴 줄이거나 같은 제목의 열린 할 일이 있다는 뜻이라, 열쇠가 있는지 보고 가른다.
        boolean moved = outcome == SuggestionOutcome.DUPLICATE
            && followUps.suggestionPublicIds(item.userId(), List.of(item.dedupeKey())).containsKey(item.dedupeKey());
        return moved ? new BackfillReport(0, 1, 0, 0, 0) : new BackfillReport(0, 0, 1, 0, 0);
    }


    /**
     * 지우지 않은 대화의 번호다. 지웠거나 정리한 대화의 옛 줄은 옮기지 않는다. 정리 작업은 옮긴 제안 줄만 지우고 옛 줄은 남기므로, 건너뛰지
     * 않으면 다음 기동이 지운 대화의 제목과 본문을 다시 저장한다.
     */
    private Set<Long> activeConversations(List<Long> conversationIds) {
        return conversations.findAllById(conversationIds.stream().distinct().toList()).stream()
            .filter(conversation -> conversation.deletedAt() == null && conversation.purgedAt() == null)
            .map(Conversation::id)
            .collect(Collectors.toSet());
    }


    private Map<Long, ProactiveCheck> ownersOf(List<Long> checkIds) {
        return checks.findAllById(checkIds.stream().distinct().toList()).stream()
            .filter(check -> check.userId() != null)
            .collect(Collectors.toMap(ProactiveCheck::id, Function.identity()));
    }


    private static void putIfPresent(Map<String, Object> target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.put(name, value);
        }
    }


    /** 옛 분류 원문을 {@code legacy} 자유 필드 하나에 담은 JSON 이다. */
    private static String detailOf(Map<String, Object> legacy) {
        try {
            return JSON.writeValueAsString(Map.of("legacy", legacy));
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot write legacy suggestion detail", ex);
        }
    }
}
