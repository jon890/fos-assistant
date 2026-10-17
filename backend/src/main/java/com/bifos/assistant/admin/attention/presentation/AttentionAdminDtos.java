package com.bifos.assistant.admin.attention.presentation;

import com.bifos.assistant.attention.application.model.AttentionMetric;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 지표 경로가 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AttentionAdminDtos {

    /**
     * 관리자 지표다. 제목, 항목 열쇠, 사용자 번호를 싣지 않는다.
     *
     * @param days 센 기간의 일 수
     * @param rows {@code trigger} 선언 순서다. 보인 항목이 없는 {@code trigger} 는 줄이 없다
     */
    public record MetricsResponse(int days, List<MetricRow> rows) {
    }

    /** 뜻은 {@code docs/features/attention.md} 의 「지표(먼저 알리기와 지금 화면의 판정)」 가 갖는다. */
    public record MetricRow(
        String trigger,
        long shown,
        long hidden,
        long snoozed,
        long acted,
        long nowShown,
        long nowHiddenWithoutAction,
        long staleShown,
        Long medianSecondsToFirstAction) {

        public static MetricRow from(AttentionMetric metric) {
            return new MetricRow(
                metric.trigger().name(),
                metric.shown(),
                metric.hidden(),
                metric.snoozed(),
                metric.acted(),
                metric.nowShown(),
                metric.nowHiddenWithoutAction(),
                metric.staleShown(),
                metric.medianSecondsToFirstAction());
        }
    }
}
