package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.StarterService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.presentation.AgentDtos.AgentView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agents;
    private final StarterService starters;
    private final CurrentUserProvider currentUser;

    /** 요청자가 쓸 수 있는 에이전트를 소개와 추천 질문까지 담아 한 번에 준다. */
    @GetMapping
    public List<AgentView> readable() {
        List<Agent> list = agents.readableBy(currentUser.require());
        Map<Long, List<String>> prompts = starters.promptsOf(list);
        return list.stream()
                .map(agent -> AgentView.from(agent, prompts.getOrDefault(agent.id(), List.of())))
                .toList();
    }
}
