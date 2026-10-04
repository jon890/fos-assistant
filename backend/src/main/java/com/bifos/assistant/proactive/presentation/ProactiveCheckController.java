package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.StatusResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 에이전트 화면의 먼저 살펴보기 절이 부른다(ADR-077). */
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
}
