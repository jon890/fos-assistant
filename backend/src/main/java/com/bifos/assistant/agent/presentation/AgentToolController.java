package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.AgentToolService;
import com.bifos.assistant.agent.application.AgentToolsetsView;
import com.bifos.assistant.agent.presentation.AgentDtos.ToolsetView;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateToolsetsRequest;
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

/** 에이전트 toolset을 읽고 바꾸는 사용자와 관리자 경로다. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentToolController {

    private final AgentService agents;
    private final AgentToolService tools;
    private final CurrentUserProvider currentUser;

    @GetMapping("/agents/{code}/tools")
    public AgentDtos.ToolsetsView read(@PathVariable String code) {
        CurrentUser user = currentUser.require();
        return view(tools.read(user, agents.requireReadable(user, code)));
    }

    @PutMapping("/agents/{code}/tools")
    public AgentDtos.ToolsetsView write(@PathVariable String code, @Valid @RequestBody UpdateToolsetsRequest request) {
        CurrentUser user = currentUser.require();
        return view(tools.writeReadable(user, code, request.enabled()));
    }

    @GetMapping("/admin/agents/{code}/tools")
    public AgentDtos.ToolsetsView readAdmin(@PathVariable String code) {
        CurrentUser user = currentUser.requireAdmin();
        return view(tools.readAsAdmin(user, code));
    }

    @PutMapping("/admin/agents/{code}/tools")
    public AgentDtos.ToolsetsView writeAdmin(
            @PathVariable String code, @Valid @RequestBody UpdateToolsetsRequest request) {
        CurrentUser user = currentUser.requireAdmin();
        return view(tools.writeAsAdmin(user, code, request.enabled()));
    }

    private static AgentDtos.ToolsetsView view(AgentToolsetsView source) {
        return new AgentDtos.ToolsetsView(
                source.toolsets().stream().map(ToolsetView::from).toList(), source.unclassifiedEnabled(),
                source.shellOrFileEnabled(), source.skillsEnabled());
    }
}
