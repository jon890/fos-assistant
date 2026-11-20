package com.bifos.assistant.admin.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.admin.usage.application.AdminOverviewService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.summary.application.UsageSummaryService;
import com.bifos.assistant.usage.summary.application.dto.MonthlyUsageSummary;
import com.bifos.assistant.usage.tree.application.RootExecutionQuery;
import com.bifos.assistant.user.application.UserDisplayNameService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 금액이 실제보다 적을 수 있다는 표시가 요약 탭이 따로 알리는 세 경우마다 켜지는지 본다. */
class AdminOverviewServiceTest {

    private static final CurrentUser ADMIN = new CurrentUser(2L, "dad@example.com", "아빠", 10L, UserRole.ADMIN);

    private final UserDisplayNameService names = mock(UserDisplayNameService.class);
    private final UsageSummaryService summaries = mock(UsageSummaryService.class);
    private final AdminOverviewService overview = new AdminOverviewService(
        names,
        mock(RootExecutionQuery.class),
        summaries,
        Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC));


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
        when(summaries.monthly(anyCollection(), any(), any())).thenReturn(new MonthlyUsageSummary(
            0, 0, 3, unpriced, 1, 0, pending, unconfirmed, unpricedSubagents, observation));

        assertThat(overview.monthly(ADMIN).incomplete()).isEqualTo(expected);
    }
}
