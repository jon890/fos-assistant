package com.bifos.assistant.attention.presentation;

import com.bifos.assistant.attention.application.AttentionMetricsService;
import com.bifos.assistant.attention.presentation.AttentionDtos.MetricRow;
import com.bifos.assistant.attention.presentation.AttentionDtos.MetricsResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 먼저 알리기의 지표를 {@code trigger} 별로 읽는 경로다. 제목과 항목 열쇠를 내지 않는다. */
@RestController
@RequestMapping("/api/v1/admin/attention")
@RequiredArgsConstructor
public class AttentionAdminController {

    private final AttentionMetricsService metrics;
    private final CurrentUserProvider currentUser;

    /** @param days 오늘부터 거꾸로 센 기간. 1 부터 90 까지다 */
    @GetMapping("/metrics")
    public MetricsResponse metrics(@RequestParam(defaultValue = "30") int days) {
        currentUser.requireAdmin();
        return new MetricsResponse(
                days, metrics.metrics(days).stream().map(MetricRow::from).toList());
    }
}
