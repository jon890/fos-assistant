package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentService agents;
    private final CurrentUserProvider currentUser;

    public AgentController(AgentService agents, CurrentUserProvider currentUser) {
        this.agents = agents;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<AgentView> readable() {
        return agents.readableBy(currentUser.require()).stream().map(AgentView::from).toList();
    }

    public record AgentView(String code, String name, String model, String visibility) {
        static AgentView from(Agent agent) {
            return new AgentView(agent.code(), agent.name(), agent.model(), agent.visibility().name());
        }
    }
}
