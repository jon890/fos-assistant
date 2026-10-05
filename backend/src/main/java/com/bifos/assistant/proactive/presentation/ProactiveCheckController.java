package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.StartedResponse;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.StatusResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 에이전트 화면의 먼저 살펴보기 절이 부른다(ADR-080). */
@RestController
@RequestMapping("/api/v1/agents/{code}/proactive-check")
@RequiredArgsConstructor
public class ProactiveCheckController {

    private final ProactiveCheckService checks;
    private final CurrentUserProvider currentUser;

    /** 살펴보기를 할 수 있는지, 막는 까닭, 요청자의 점검 대화, 요청자의 마지막 살펴보기를 준다. */
    @GetMapping
    public StatusResponse status(@PathVariable String code) {
        return StatusResponse.from(checks.status(currentUser.require(), code));
    }

    /** 살펴보기를 시작하고 곧바로 202 와 점검 대화의 공개 식별자를 준다. 결과는 그 대화의 SSE 와 이력으로 간다. */
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartedResponse start(@PathVariable String code) {
        return new StartedResponse(checks.start(currentUser.require(), code, CheckTrigger.MANUAL));
    }

}
