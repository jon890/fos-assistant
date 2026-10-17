package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.model.AgentToolsetsView;
import com.bifos.assistant.agent.application.toolset.AgentToolService;
import com.bifos.assistant.agent.presentation.ToolsetAdminDtos.ToolsetView;
import com.bifos.assistant.agent.presentation.ToolsetAdminDtos.UpdateToolsetsRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 에이전트 toolset 을 읽고 바꾸는 경로다. */
@RestController
@RequestMapping("/api/v1/admin/agents/{code}/tools")
@RequiredArgsConstructor
public class AgentToolAdminController {

    private final AgentToolService tools;
    private final CurrentUserProvider currentUser;


    @GetMapping
    public ToolsetAdminDtos.ToolsetsView read(@PathVariable String code) {
        CurrentUser user = currentUser.requireAdmin();
        return view(tools.readAsAdmin(user, code));
    }


    @PutMapping
    public ToolsetAdminDtos.ToolsetsView write(
        @PathVariable String code, @Valid @RequestBody UpdateToolsetsRequest request) {
        CurrentUser user = currentUser.requireAdmin();
        return view(tools.writeAsAdmin(user, code, request.enabled()));
    }


    private static ToolsetAdminDtos.ToolsetsView view(AgentToolsetsView source) {
        return new ToolsetAdminDtos.ToolsetsView(
            source.toolsets().stream().map(ToolsetView::from).toList(), source.unclassifiedEnabled(),
            source.shellOrFileEnabled(), source.skillsEnabled());
    }
}
