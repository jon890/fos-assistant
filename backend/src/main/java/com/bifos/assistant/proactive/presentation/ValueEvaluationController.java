package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.ValueEvaluationService;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.EvaluationRequest;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.EvaluationResponse;
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

/** 요청자의 살펴보기 후보만 평가하고 읽는다. 실행이나 승인 API 가 아니다. */
@RestController
@RequiredArgsConstructor
public class ValueEvaluationController {

    private final ValueEvaluationService evaluations;
    private final CurrentUserProvider currentUser;

    @PostMapping("/api/v1/proactive-checks/{checkId}/value-evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationResponse evaluate(@PathVariable Long checkId, @Valid @RequestBody EvaluationRequest request) {
        return EvaluationResponse.from(evaluations.evaluate(currentUser.require(), checkId, request.provider()));
    }

    @PostMapping("/api/v1/value-evaluations/{id}/replays")
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationResponse replay(@PathVariable Long id, @Valid @RequestBody EvaluationRequest request) {
        return EvaluationResponse.from(evaluations.replay(currentUser.require(), id, request.provider()));
    }

    @GetMapping("/api/v1/value-evaluations/{id}")
    public EvaluationResponse read(@PathVariable Long id) {
        return EvaluationResponse.from(evaluations.read(currentUser.require(), id));
    }
}
