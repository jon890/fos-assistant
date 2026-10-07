package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.DecisionFeedbackExporter;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 요청자의 판단 피드백만 offline replay 읽기 모델로 낸다. 관리자도 남의 기록을 읽지 못한다. 화면은 없다(ADR-20261007 decision-feedback). */
@RestController
@RequiredArgsConstructor
public class DecisionFeedbackController {

    static final int MAX_DAYS = 365;

    private final DecisionFeedbackExporter exporter;
    private final CurrentUserProvider currentUser;

    @GetMapping("/api/v1/decision-feedback/export")
    public DecisionFeedbackExport export(@RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "days must be between 1 and 365");
        }
        return exporter.export(currentUser.require(), Duration.ofDays(days));
    }
}
