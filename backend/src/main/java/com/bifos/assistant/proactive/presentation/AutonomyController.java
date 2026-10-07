package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.AutonomyPolicyService;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.AutonomyDecisionResponse;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.AutonomyPreferenceBody;
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

/** 요청자의 평가만 판정하고 요청자의 동의만 읽고 바꾼다. 화면은 아직 없다(ADR-20261007 autonomy-policy). */
@RestController
@RequiredArgsConstructor
public class AutonomyController {

    private final AutonomyPolicyService autonomy;
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
}
