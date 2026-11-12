package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentCapabilitiesReader;
import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.PrimaryAgents;
import com.bifos.assistant.agent.application.dto.AgentCapabilities;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.presentation.AgentDtos.AgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.ChangeIntroductionRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.ChangeVisibilityRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.CreateOwnAgentRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final AgentCapabilitiesReader capabilitiesReader;
    private final PrimaryAgents primaryAgents;


    /** 요청자가 쓸 수 있는 에이전트를 준다. 추천 질문은 에이전트마다 따로 읽는다. */
    @GetMapping
    public List<AgentView> readable() {
        CurrentUser user = currentUser.require();
        List<Agent> readable = agents.readableBy(user);
        Map<Long, AgentCapabilities> capabilities = capabilitiesReader.read(readable);
        Optional<Long> primaryId = primaryAgents.of(user).map(Agent::id);
        return readable.stream()
            .map(agent -> view(
                user,
                agent,
                capabilities.getOrDefault(agent.id(), AgentCapabilities.NONE),
                primaryId.filter(id -> Objects.equals(id, agent.id())).isPresent()))
            .toList();
    }


    /** 요청자의 에이전트를 profile 까지 만든다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentView create(@RequestBody CreateOwnAgentRequest request) {
        CurrentUser user = currentUser.require();
        return view(user, lifecycle.create(user, request.name(), request.visibility()));
    }


    /** 주인이나 {@code ADMIN} 이 이름과 소개, 아이콘, 색을 바꾼다(docs/features/agent-team/spec.md 의 REQ1). */
    @PatchMapping("/{code}/introduction")
    public AgentView changeIntroduction(@PathVariable String code, @RequestBody ChangeIntroductionRequest request) {
        CurrentUser user = currentUser.require();
        return view(user, lifecycle.changeIntroduction(user, code, request.toIntroduction()));
    }


    /** 주인이나 {@code ADMIN} 이 공개 범위를 바꾼다. 주인은 그대로 남는다. */
    @PatchMapping("/{code}/visibility")
    public AgentView changeVisibility(@PathVariable String code, @Valid @RequestBody ChangeVisibilityRequest request) {
        CurrentUser user = currentUser.require();
        return view(user, lifecycle.changeVisibility(user, code, request.visibility()));
    }


    /** 주인이나 {@code ADMIN} 이 지운다. 대화는 읽기만 되게 남는다. */
    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        lifecycle.delete(currentUser.require(), code);
    }


    /**
     * 할 수 있는 일은 요청자가 읽을 수 있는 에이전트에만 채운다. {@code ADMIN} 이 남의 비공개 에이전트를 고친 응답에는 주인이 붙인 연결의
     * 이름을 싣지 않는다. 관리자도 남의 에이전트의 연결은 보지 않는다(docs/features/agent-skill.md 「에이전트 화면」).
     */
    private AgentView view(CurrentUser user, Agent agent) {
        AgentCapabilities capabilities = agent.isReadableBy(user.id())
            ? capabilitiesReader.read(List.of(agent)).getOrDefault(agent.id(), AgentCapabilities.NONE)
            : AgentCapabilities.NONE;
        boolean primary = primaryAgents.of(user).map(Agent::id).filter(id -> Objects.equals(id, agent.id())).isPresent();
        return view(user, agent, capabilities, primary);
    }


    private AgentView view(CurrentUser user, Agent agent, AgentCapabilities capabilities, boolean primary) {
        return AgentView.from(
            agent,
            agents.isEditableBy(user, agent),
            Objects.equals(agent.ownerUserId(), user.id()),
            primary,
            capabilities);
    }
}
