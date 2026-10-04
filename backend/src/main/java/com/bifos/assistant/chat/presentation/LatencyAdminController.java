package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.FirstResponseLatencyService;
import com.bifos.assistant.chat.presentation.ChatDtos.LatencySummaryView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 사용자 turn 의 첫 반응 시간을 날짜와 모델 단계별로 읽는다(ADR-063). 요청한 관리자 자신의 실행만 센다. */
@RestController
@RequestMapping("/api/v1/admin/usage")
@RequiredArgsConstructor
public class LatencyAdminController {

    private final CurrentUserProvider currentUser;
    private final FirstResponseLatencyService latency;

    @GetMapping("/latency")
    public LatencySummaryView latency(@RequestParam(defaultValue = "30") int days) {
        return LatencySummaryView.from(
                latency.summarize(currentUser.requireAdmin().id(), days));
    }
}
