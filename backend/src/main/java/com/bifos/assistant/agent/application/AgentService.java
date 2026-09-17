package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentService {
    private final AgentRepository agents;

    public List<Agent> readableBy(CurrentUser user) {
        return agents.findByEnabledTrueOrderByCodeAsc().stream()
                .filter(agent -> agent.isReadableBy(user.id()))
                .toList();
    }

    public Agent requireReadable(CurrentUser user, String code) {
        Agent agent = agents.findByCode(code)
                .orElseThrow(() -> notFound());
        if (!agent.isReadableBy(user.id())) {
            throw notFound();
        }
        return agent;
    }

    public Agent requireById(Long id) {
        return agents.findById(id).orElseThrow(() -> notFound());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
    }
}
