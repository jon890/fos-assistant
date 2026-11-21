package com.bifos.assistant.admin.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.admin.usage.application.AdminOverviewService;
import com.bifos.assistant.admin.usage.application.dto.OverviewMonthly;
import com.bifos.assistant.quality.application.EvaluationUserService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.summary.application.UsageSummaryService;
import com.bifos.assistant.usage.summary.application.dto.MonthlyUsageSummary;
import com.bifos.assistant.usage.tree.application.RootExecutionQuery;
import com.bifos.assistant.usage.tree.application.dto.RecentFailures;
import com.bifos.assistant.user.application.UserDisplayNameService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 금액이 실제보다 적을 수 있다는 표시가 요약 탭이 따로 알리는 세 경우마다 켜지는지, 평가 사용자를 「평가」 로 따로 세는지 본다. */
class AdminOverviewServiceTest {

    private static final CurrentUser ADMIN = new CurrentUser(2L, "dad@example.com", "아빠", 10L, UserRole.ADMIN);

    private final UserDisplayNameService names = mock(UserDisplayNameService.class);
    private final UsageSummaryService summaries = mock(UsageSummaryService.class);
    private final EvaluationUserService evaluationUsers = mock(EvaluationUserService.class);
    private final RootExecutionQuery roots = mock(RootExecutionQuery.class);
    private final AdminOverviewService overview = new AdminOverviewService(
        names,
        roots,
        summaries,
        evaluationUsers,
        Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC));


    @DisplayName("요약 탭이 따로 알리는 세 경우 가운데 하나라도 있으면 금액이 적을 수 있다고 표시한다")
    @ParameterizedTest(name = "[{index}] 가격 없음 {0}, 대기 {1}, 확인 못 함 {2}, 도우미 가격 없음 {3}, 관측 미확인 {4} → {5}")
    @CsvSource({
        "0, 0, 0, 0, 0, false",
        "1, 0, 0, 0, 0, true",
        "0, 1, 0, 0, 0, true",
        "0, 0, 1, 0, 0, true",
        "0, 0, 0, 1, 0, true",
        "0, 0, 0, 0, 1, true"})
    void flagsIncompleteForEachMissingAmount(
        long unpriced, long pending, long unconfirmed, long unpricedSubagents, long observation, boolean expected) {
        when(names.namesInGroup(10L)).thenReturn(Map.of(2L, "아빠"));
        when(evaluationUsers.idIn(10L)).thenReturn(Optional.empty());
        when(summaries.monthly(anyCollection(), any(), any())).thenReturn(new MonthlyUsageSummary(
            0, 0, 3, unpriced, 1, 0, pending, unconfirmed, unpricedSubagents, observation));

        assertThat(overview.monthly(ADMIN).incomplete()).isEqualTo(expected);
    }


    @Test
    @DisplayName("평가 사용자는 그룹 합계에서 빠지고 「평가」 합계로 따로 센다")
    void evaluationUserIsCountedSeparately() {
        when(names.namesInGroup(10L)).thenReturn(Map.of(2L, "아빠", 9L, "평가 사용자"));
        when(evaluationUsers.idIn(10L)).thenReturn(Optional.of(9L));
        when(summaries.monthly(eq(Set.of(2L)), any(), any())).thenReturn(new MonthlyUsageSummary(5000, 3000, 4, 0, 1, 0, 0, 0, 0, 0));
        when(summaries.monthly(eq(Set.of(9L)), any(), any())).thenReturn(new MonthlyUsageSummary(1000, 2000, 6, 1, 0, 0, 0, 0, 0, 0));

        OverviewMonthly monthly = overview.monthly(ADMIN);

        assertThat(monthly.totalExecutions()).isEqualTo(4);
        assertThat(monthly.actualCostMicros()).isEqualTo(3000);
        assertThat(monthly.evaluation()).isEqualTo(new OverviewMonthly.Evaluation(7, 2000, true));
    }


    @Test
    @DisplayName("최근 실패 카드는 평가 사용자의 실행을 세지 않는다")
    void failuresExcludeEvaluationUser() {
        when(names.namesInGroup(10L)).thenReturn(Map.of(2L, "아빠", 9L, "평가 사용자"));
        when(evaluationUsers.idIn(10L)).thenReturn(Optional.of(9L));
        when(roots.recentFailures(anyCollection(), any(), anyInt())).thenReturn(new RecentFailures(0, List.of()));

        overview.failures(ADMIN);

        verify(roots).recentFailures(eq(Set.of(2L)), any(), anyInt());
    }
}
