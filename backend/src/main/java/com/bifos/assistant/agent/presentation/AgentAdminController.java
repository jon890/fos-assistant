package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.admin.application.AgentAdminService;
import com.bifos.assistant.agent.admin.application.model.AgentCreateCommand;
import com.bifos.assistant.agent.admin.application.model.AgentUpdateCommand;
import com.bifos.assistant.agent.presentation.AgentDtos.AdminAgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.CreateAgentRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateAgentRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/agents")
@RequiredArgsConstructor
public class AgentAdminController {
    private final AgentAdminService adminAgents;
    private final CurrentUserProvider currentUser;

    @PostMapping
    public AdminAgentView create(@Valid @RequestBody CreateAgentRequest request) {
        currentUser.requireAdmin();
        return AdminAgentView.from(adminAgents.create(new AgentCreateCommand(
                request.code(),
                request.name(),
                request.hermesProfile(),
                request.apiBaseUrl(),
                request.costMode(),
                request.credentialScope(),
                request.visibility(),
                request.ownerEmail(),
                request.flow())));
    }

    @GetMapping
    public List<AdminAgentView> list() {
        currentUser.requireAdmin();
        return adminAgents.list().stream().map(AdminAgentView::from).toList();
    }

    @PatchMapping("/{code}")
    public AdminAgentView update(@PathVariable String code, @Valid @RequestBody UpdateAgentRequest request) {
        currentUser.requireAdmin();
        return AdminAgentView.from(adminAgents.update(
                code,
                new AgentUpdateCommand(
                        request.enabled(),
                        request.visibility(),
                        request.ownerEmail(),
                        request.apiBaseUrl(),
                        request.proactiveCheckWritesAllowed())));
    }
}
