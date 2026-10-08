package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.ValueEvaluationOverviews;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.EvaluationOverviewResponse;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.EvaluationRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 자기 살펴보기 한 건의 가치 평가와 행동 정책을 돌리고 다시 읽는 경로다. 판단 profile 을 부르는 비용을 관리자 화면 밖으로 넓히지 않는다. */
@RestController
@RequiredArgsConstructor
public class ValueEvaluationAdminController {

    private final ValueEvaluationOverviews overviews;
    private final CurrentUserProvider currentUser;

    @GetMapping("/api/v1/admin/agents/{code}/value-evaluation")
    public EvaluationOverviewResponse latest(@PathVariable String code) {
        CurrentUser admin = currentUser.requireAdmin();
        return EvaluationOverviewResponse.from(overviews.latest(admin, code));
    }

    @PostMapping("/api/v1/admin/proactive-checks/{checkId}/value-evaluation-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationOverviewResponse run(@PathVariable Long checkId, @Valid @RequestBody EvaluationRequest request) {
        CurrentUser admin = currentUser.requireAdmin();
        return EvaluationOverviewResponse.from(overviews.run(admin, checkId, request.provider()));
    }
}
