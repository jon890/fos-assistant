package com.bifos.assistant.admin.usage.application;

import com.bifos.assistant.admin.usage.application.dto.OverviewFailures;
import com.bifos.assistant.admin.usage.application.dto.OverviewMonthly;
import com.bifos.assistant.quality.application.EvaluationUserService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.summary.application.UsageSummaryService;
import com.bifos.assistant.usage.summary.application.dto.MonthlyUsageSummary;
import com.bifos.assistant.usage.tree.application.RootExecutionQuery;
import com.bifos.assistant.usage.tree.application.dto.RecentFailures;
import com.bifos.assistant.user.application.UserDisplayNameService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자 영역 첫 화면(요약)의 두 카드를 그룹 전체로 센다.
 *
 * <p>그룹의 모든 사용자를 세지만 남의 실행은 세기만 한다. 대화와 도구 원본은 내지 않고, 실행 상세는 지금처럼 본인 것만 열린다.
 * 근거는 {@code docs/features/admin-area/spec.md} 의 DECISION10 이다.
 *
 * <p>평가 전용 사용자는 사용자 집계에서 빼고 사용량 카드에 「평가」 한 줄로 따로 센다(docs/features/agent-quality/spec.md 의 REQ2).
 * 평가 실행의 실패는 품질 화면이 보이므로 실패 카드에서도 뺀다.
 */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    /**
     * 가족이 사는 곳의 달력으로 날과 달을 끊는다.
     *
     * <p>같은 시간대를 {@code UsageController.HOUSEHOLD_ZONE}, {@code UsageBreakdownAdminController.HOUSEHOLD_ZONE},
     * {@code AgentExecutionRepository.sumByDayBetween} 의 질의도 갖는다. 가족이 사는 곳이 바뀌면 네 자리를 함께 고친다.
     */
    private static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Asia/Seoul");

    /** 오늘을 포함해 세는 날 수다. 주마다 한 번 열어도 빠짐없이 보이는 기간이다. */
    private static final int FAILURE_DAYS = 7;

    /** 카드에 보이는 최근 실패 줄 수다. */
    private static final int RECENT_LIMIT = 3;

    private final UserDisplayNameService userNames;
    private final RootExecutionQuery rootExecutions;
    private final UsageSummaryService summaries;
    private final EvaluationUserService evaluationUsers;
    private final Clock clock;


    /** 서울 날짜로 오늘을 포함한 7일 동안 그룹에서 실패한 루트 실행 수와 최근 세 건이다. */
    public OverviewFailures failures(CurrentUser admin) {
        Map<Long, String> names = members(admin, evaluationUsers.idIn(admin.groupId()));
        Instant since = LocalDate.now(clock.withZone(HOUSEHOLD_ZONE))
            .minusDays(FAILURE_DAYS - 1L)
            .atStartOfDay(HOUSEHOLD_ZONE)
            .toInstant();
        RecentFailures failures = rootExecutions.recentFailures(names.keySet(), since, RECENT_LIMIT);
        return new OverviewFailures(
            since,
            failures.total(),
            failures.recent().stream()
                .map(row -> new OverviewFailures.Line(
                    row.id(),
                    names.get(row.userId()),
                    row.agentName(),
                    row.startedAt(),
                    row.errorCode(),
                    row.userId().equals(admin.id())))
                .toList());
    }


    /** 그룹 전체의 이번 달 합계다. 달의 경계와 금액 계산은 「사용량과 비용」 요약 탭과 같다. */
    public OverviewMonthly monthly(CurrentUser admin) {
        YearMonth month = YearMonth.now(clock.withZone(HOUSEHOLD_ZONE));
        Instant from = month.atDay(1).atStartOfDay(HOUSEHOLD_ZONE).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(HOUSEHOLD_ZONE).toInstant();
        Optional<Long> evaluationId = evaluationUsers.idIn(admin.groupId());
        MonthlyUsageSummary sum = summaries.monthly(members(admin, evaluationId).keySet(), from, to);
        OverviewMonthly.Evaluation evaluation = evaluationId
            .map(id -> summaries.monthly(Set.of(id), from, to))
            .map(found -> new OverviewMonthly.Evaluation(
                found.pricedExecutions() + found.unpricedExecutions(), found.actualMicros(), incomplete(found)))
            .orElse(null);
        return new OverviewMonthly(
            month.toString(),
            sum.pricedExecutions() + sum.unpricedExecutions(),
            sum.subscriptionExecutions(),
            sum.actualMicros(),
            incomplete(sum),
            evaluation);
    }


    /** 금액이 실제보다 적을 수 있는가. 「사용량과 비용」 요약 탭이 따로 알리는 경우 가운데 하나라도 있으면 참이다. */
    private static boolean incomplete(MonthlyUsageSummary sum) {
        return sum.unpricedExecutions()
            + sum.pendingSubagents()
            + sum.unconfirmedSubagents()
            + sum.unpricedSubagents()
            + sum.observationIncompleteExecutions() > 0;
    }


    /** 그룹 사용자의 이름이다. 평가 전용 사용자는 뺀다. */
    private Map<Long, String> members(CurrentUser admin, Optional<Long> evaluationId) {
        Map<Long, String> names = new HashMap<>(userNames.namesInGroup(admin.groupId()));
        evaluationId.ifPresent(names::remove);
        return names;
    }
}
