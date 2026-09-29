package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentDtos.AdminAgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.CreateAgentRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateAgentRequest;
import com.bifos.assistant.orchestration.application.FlowRegistry;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
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
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/admin/agents")
@RequiredArgsConstructor
public class AgentAdminController {
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;
    private final AgentLifecycleService lifecycle;
    private final AgentEndpointProbe endpointProbe;
    private final FlowRegistry flows;

    @PostMapping
    @Transactional
    public AdminAgentView create(@Valid @RequestBody CreateAgentRequest request) {
        currentUser.requireAdmin();
        if (agents.findByCode(request.code()).isPresent()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this agent code is already used");
        }
        Long ownerId = ownerId(request.visibility(), request.ownerEmail());
        // 잘못된 주소나 profile 로 등록하면 그 에이전트의 모든 대화가 실패한다. 저장하기 전에 닿는지 본다.
        endpointProbe.requireReachable(request.apiBaseUrl(), request.hermesProfile());
        if (request.visibility() == AgentVisibility.GROUP) {
            lifecycle.requireGroupSafe(request.apiBaseUrl(), request.hermesProfile());
        }
        Agent agent = Agent.of(request.code(), request.name(),
                request.hermesProfile(), request.apiBaseUrl(),
                request.costMode(), request.credentialScope(), request.visibility(), ownerId);
        agent.assignFlow(requireKnownFlow(request.flow()));
        return AdminAgentView.from(agents.save(agent));
    }

    /**
     * 모르는 흐름 이름을 저장하지 못하게 막는다.
     *
     * <p>기동할 때도 같은 것을 확인한다. 여기서 막는 것은 이미 도는 서버를 다음 기동에서 세우지 않기
     * 위해서다.
     */
    private String requireKnownFlow(String flow) {
        if (flow == null || flow.isBlank() || flows.find(flow) != null) {
            return flow;
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "no such flow");
    }

    @GetMapping
    public List<AdminAgentView> list() {
        currentUser.requireAdmin();
        return agents.findAll().stream()
                // 지운 에이전트는 되살리지 못하므로 관리 목록에도 두지 않는다.
                .filter(agent -> !agent.isDeleted())
                .map(AdminAgentView::from)
                .toList();
    }

    @PatchMapping("/{code}")
    @Transactional
    public AdminAgentView update(@PathVariable String code,
            @Valid @RequestBody UpdateAgentRequest request) {
        currentUser.requireAdmin();
        Agent agent = requireAgentForUpdate(code);
        // 주인은 공개 범위와 별개다(ADR-033). 새 주인을 주지 않으면 그룹으로 바꿔도 지금 주인이 남는다.
        Long ownerId = request.ownerEmail() == null || request.ownerEmail().isBlank()
                ? agent.ownerUserId()
                : ownerId(request.visibility(), request.ownerEmail());
        if (request.visibility() == AgentVisibility.PRIVATE && ownerId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a private agent needs an owner");
        }
        String apiBaseUrl = effectiveApiBaseUrl(agent, request.apiBaseUrl());
        if (request.enabled() && request.visibility() == AgentVisibility.GROUP) {
            lifecycle.requireGroupSafe(apiBaseUrl, agent.hermesProfile());
        }
        agent.changeAccess(request.enabled(), request.visibility(), ownerId);
        if (!apiBaseUrl.equals(agent.apiBaseUrl())) agent.changeApiBaseUrl(apiBaseUrl);
        return AdminAgentView.from(agents.save(agent));
    }

    /**
     * 주소를 바꾸는 요청이면 닿는지 본 뒤에 바꾼다.
     *
     * <p>비어 있거나 지금 값과 같으면 아무것도 하지 않는다. 다른 것만 고치는 요청이 주소를 지우거나
     * 쓸데없이 Hermes 를 부르지 않게 한다. 끝의 {@code /} 만 다른 것도 같은 값으로 본다.
     */
    private String effectiveApiBaseUrl(Agent agent, String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) return agent.apiBaseUrl();
        String next = stripTrailingSlash(apiBaseUrl.strip());
        if (next.equals(agent.apiBaseUrl())) return agent.apiBaseUrl();
        endpointProbe.requireReachable(next, agent.hermesProfile());
        return next;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 고칠 에이전트를 잠그고 읽는다. 지운 에이전트는 없는 에이전트와 같다.
     *
     * <p>여기서 막지 않으면 관리자가 {@code enabled=true} 로 지운 에이전트를 되살린다. 그 profile 은 이미
     * 거둬졌을 수 있다.
     */
    private Agent requireAgentForUpdate(String code) {
        Agent agent = agents.findByCodeForUpdate(code)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        return agent;
    }

    /**
     * 받은 메일 주소의 사용자를 주인으로 고른다.
     *
     * <p>비어 있으면 자기만 보는 에이전트는 거절하고 그룹 공개 에이전트는 주인 없이 둔다. 없는 사용자면
     * 공개 범위와 무관하게 거절한다.
     */
    private Long ownerId(AgentVisibility visibility, String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            if (visibility == AgentVisibility.PRIVATE) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "a private agent needs an owner");
            }
            return null;
        }
        return users.findByEmail(ownerEmail)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "no such user"))
                .id();
    }
}
