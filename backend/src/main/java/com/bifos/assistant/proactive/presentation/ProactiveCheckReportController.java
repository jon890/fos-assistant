package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 지금 화면에서 보고를 열었을 때만 호출한다. */
@RestController
@RequestMapping("/api/v1/proactive-checks")
@RequiredArgsConstructor
public class ProactiveCheckReportController {

    private final ProactiveCheckService checks;
    private final CurrentUserProvider currentUser;

    @PostMapping("/{checkId}/report/open")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void open(@PathVariable Long checkId) {
        checks.openReport(currentUser.require(), checkId);
    }
}
