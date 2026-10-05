package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.KnownFlows;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.presentation.AgentDtos.AgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.ChangeVisibilityRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.CreateOwnAgentRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agents;
    private final CurrentUserProvider currentUser;
    private final AgentLifecycleService lifecycle;
    private final KnownFlows flows;

    /** 요청자가 쓸 수 있는 에이전트를 준다. 추천 질문은 에이전트마다 따로 읽는다. */
    @GetMapping
    public List<AgentView> readable() {
        CurrentUser user = currentUser.require();
        return agents.readableBy(user).stream().map(agent -> view(user, agent)).toList();
    }

    /** 요청자의 에이전트를 profile 까지 만든다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentView create(@RequestBody CreateOwnAgentRequest request) {
        CurrentUser user = currentUser.require();
        return view(user, lifecycle.create(user, request.name(), request.visibility()));
    }

    /** 주인이나 {@code ADMIN} 이 공개 범위를 바꾼다. 주인은 그대로 남는다. */
    @PatchMapping("/{code}/visibility")
    public AgentView changeVisibility(
            @PathVariable String code, @Valid @RequestBody ChangeVisibilityRequest request) {
        CurrentUser user = currentUser.require();
        return view(user, lifecycle.changeVisibility(user, code, request.visibility()));
    }

    /** 주인이나 {@code ADMIN} 이 지운다. 대화는 읽기만 되게 남는다. */
    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        lifecycle.delete(currentUser.require(), code);
    }

    private AgentView view(CurrentUser user, Agent agent) {
        return AgentView.from(
                agent,
                agents.isEditableBy(user, agent),
                Objects.equals(agent.ownerUserId(), user.id()),
                !flows.known(agent.flow()));
    }
}
