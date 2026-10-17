package com.bifos.assistant.admin.usage.presentation;

import com.bifos.assistant.chat.application.model.LatencyRow;
import com.bifos.assistant.chat.application.model.LatencyStat;
import com.bifos.assistant.chat.application.model.LatencySummary;
import com.bifos.assistant.model.domain.type.ModelTier;
import java.time.LocalDate;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 지연 요약 경로가 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LatencyAdminDtos {

    /**
     * 관리자 사용량 화면의 첫 반응 시간 절이 받는 집계다.
     *
     * @param days 집계한 기간의 일수
     * @param rows 날짜 오름차순이고 같은 날짜 안에서는 단계 순서다
     */
    public record LatencySummaryView(int days, List<LatencyRowView> rows) {
        static LatencySummaryView from(LatencySummary summary) {
            return new LatencySummaryView(
                summary.days(),
                summary.rows().stream().map(LatencyRowView::from).toList());
        }
    }

    /**
     * @param date {@code Asia/Seoul} 의 날짜
     * @param modelTier 모델 단계. 단계를 고르지 않은 실행은 null
     * @param turns 답 메시지가 있는 사용자 turn 수
     * @param firstResponse 요청을 받은 때부터 첫 조각까지
     * @param toSubmit 요청을 받은 때부터 제출까지
     * @param toFirstDelta 제출한 때부터 첫 조각까지
     */
    public record LatencyRowView(
        LocalDate date,
        ModelTier modelTier,
        long turns,
        LatencyStatView firstResponse,
        LatencyStatView toSubmit,
        LatencyStatView toFirstDelta) {
        static LatencyRowView from(LatencyRow row) {
            return new LatencyRowView(
                row.date(),
                row.modelTier(),
                row.turns(),
                LatencyStatView.from(row.firstResponse()),
                LatencyStatView.from(row.toSubmit()),
                LatencyStatView.from(row.toFirstDelta()));
        }
    }

    /**
     * 지표 하나의 건수와 백분위다. 값이 없는 지표는 {@code count} 가 0 이고 두 백분위가 null 이다.
     *
     * @param p50Ms 중앙값. 밀리초
     * @param p90Ms 90번째 백분위. 밀리초
     */
    public record LatencyStatView(long count, Long p50Ms, Long p90Ms) {
        static LatencyStatView from(LatencyStat stat) {
            return new LatencyStatView(stat.count(), stat.p50Ms(), stat.p90Ms());
        }
    }
}
