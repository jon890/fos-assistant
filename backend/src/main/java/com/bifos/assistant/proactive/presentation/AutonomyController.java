package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.AutonomyPolicyService;
import com.bifos.assistant.proactive.application.SurfacedProblems;
import com.bifos.assistant.proactive.application.model.DecisionReaction;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.AutonomyDecisionResponse;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.AutonomyPreferenceBody;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.DecisionReactionRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 요청자의 평가만 판정하고 요청자의 동의만 읽고 바꾼다. 관리자 영역의 가치 평가 절이 결과를 읽는다(ADR-20261007 autonomy-policy). */
@RestController
@RequiredArgsConstructor
public class AutonomyController {

    private final AutonomyPolicyService autonomy;
    private final SurfacedProblems surfacedProblems;
    private final CurrentUserProvider currentUser;

    @PostMapping("/api/v1/value-evaluations/{id}/autonomy-decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public List<AutonomyDecisionResponse> decide(@PathVariable Long id) {
        return autonomy.decide(currentUser.require(), id).stream()
                .map(AutonomyDecisionResponse::from)
                .toList();
    }

    @GetMapping("/api/v1/autonomy/preference")
    public AutonomyPreferenceBody preference() {
        return new AutonomyPreferenceBody(autonomy.readOnlyExecutionConsented(currentUser.require()));
    }

    @PutMapping("/api/v1/autonomy/preference")
    public AutonomyPreferenceBody changePreference(@Valid @RequestBody AutonomyPreferenceBody body) {
        return new AutonomyPreferenceBody(
                autonomy.changeReadOnlyExecution(currentUser.require(), body.readOnlyExecution()));
    }

    /** 매일 루프가 보인 판정에 반응을 남긴다. 기록만 하고 할 일, 승인 줄, 실행을 만들지 않는다. */
    @PutMapping("/api/v1/autonomy-decisions/{id}/reaction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void react(@PathVariable Long id, @Valid @RequestBody DecisionReactionRequest body) {
        surfacedProblems.react(currentUser.require(), id, DecisionReaction.parse(body.reaction()));
    }
}
