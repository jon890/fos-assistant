package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.presentation.AgentDtos.AgentView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agents;
    private final CurrentUserProvider currentUser;

    /** 요청자가 쓸 수 있는 에이전트를 준다. 추천 질문은 에이전트마다 따로 읽는다. */
    @GetMapping
    public List<AgentView> readable() {
        return agents.readableBy(currentUser.require()).stream().map(AgentView::from).toList();
    }
}
