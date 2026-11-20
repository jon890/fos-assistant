package com.bifos.assistant.admin.usage.presentation;

import com.bifos.assistant.admin.usage.application.dto.OverviewFailures;
import com.bifos.assistant.admin.usage.application.dto.OverviewMonthly;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 요약 경로가 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AdminOverviewDtos {

    /** 그룹 전체의 최근 실패다. */
    public record FailuresView(Instant since, long total, List<FailureLineView> recent) {

        static FailuresView of(OverviewFailures failures) {
            return new FailuresView(
                failures.since(),
                failures.total(),
                failures.recent().stream().map(FailureLineView::of).toList());
        }
    }

    /**
     * 실패 한 줄이다. 대화 번호와 도구 원본은 없다.
     *
     * @param mine 요청한 관리자 자신의 실행이면 참. 화면은 이 줄만 실행 상세로 잇는다
     */
    public record FailureLineView(
        Long id, String userName, String agentName, Instant startedAt, String errorCode, boolean mine) {

        static FailureLineView of(OverviewFailures.Line line) {
            return new FailureLineView(
                line.id(), line.userName(), line.agentName(), line.startedAt(), line.errorCode(), line.mine());
        }
    }

    /**
     * 그룹 전체의 이번 달 합계다.
     *
     * @param incomplete 금액을 확인하지 못한 실행이나 도우미가 있어 금액이 실제보다 적을 수 있으면 참
     */
    public record MonthlyView(
        String month,
        String currency,
        long totalExecutions,
        long subscriptionExecutions,
        long actualCostMicros,
        boolean incomplete) {

        static MonthlyView of(OverviewMonthly monthly) {
            return new MonthlyView(
                monthly.month(),
                "USD",
                monthly.totalExecutions(),
                monthly.subscriptionExecutions(),
                monthly.actualCostMicros(),
                monthly.incomplete());
        }
    }
}
