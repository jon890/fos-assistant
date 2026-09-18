package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.application.AgentModelSync;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentDtos.AdminAgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.CreateAgentRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.ModelOptionView;
import com.bifos.assistant.agent.presentation.AgentDtos.ModelSyncView;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateAgentRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateModelOptionsRequest;
import com.bifos.assistant.hermes.HermesModelClient;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/agents")
@RequiredArgsConstructor
public class AgentAdminController {
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;
    private final HermesModelClient hermesModels;
    private final AgentModelSync modelSync;
    private final AgentModelSelector models;
    private final AgentEndpointProbe endpointProbe;
    private final FlowRegistry flows;

    @PostMapping
    public AdminAgentView create(@Valid @RequestBody CreateAgentRequest request) {
        currentUser.requireAdmin();
        if (agents.findByCode(request.code()).isPresent()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this agent code is already used");
        }
        Long ownerId = ownerId(request.visibility(), request.ownerEmail());
        String model = hermesModels.readModel(request.apiBaseUrl(), request.hermesProfile());
        if (model == null) {
            throw new ApiException(ErrorCode.AGENT_MODEL_UNKNOWN, "could not read the agent model");
        }
        Agent agent = Agent.of(request.code(), request.name(),
                request.hermesProfile(), request.apiBaseUrl(), request.provider(), model,
                request.costMode(), request.credentialScope(), request.visibility(), ownerId);
        agent.syncModel(model);
        agent.assignFlow(requireKnownFlow(request.flow()));
        Agent saved = agents.save(agent);
        // 목록이 비어 있으면 첫 실행이 쓸 모델을 찾지 못한다. 등록하는 자리에서 1순위를 만든다.
        models.seedFirst(saved, new ModelOption(request.provider(), model));
        return AdminAgentView.from(saved, models.optionsOf(saved));
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
                .map(agent -> AdminAgentView.from(agent, models.optionsOf(agent)))
                .toList();
    }

    @PatchMapping("/{code}")
    public AdminAgentView update(@PathVariable String code,
            @Valid @RequestBody UpdateAgentRequest request) {
        currentUser.requireAdmin();
        Agent agent = requireAgent(code);
        Long ownerId = request.visibility() == AgentVisibility.PRIVATE
                ? (request.ownerEmail() == null || request.ownerEmail().isBlank()
                        ? agent.ownerUserId()
                        : ownerId(request.visibility(), request.ownerEmail()))
                : null;
        if (request.visibility() == AgentVisibility.PRIVATE && ownerId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a private agent needs an owner");
        }
        agent.changeAccess(request.enabled(), request.visibility(), ownerId);
        applyApiBaseUrl(agent, request.apiBaseUrl());
        Agent saved = agents.save(agent);
        return AdminAgentView.from(saved, models.optionsOf(saved));
    }

    /** 이 에이전트가 쓸 모델을 순위 순서로 준다. */
    @GetMapping("/{code}/models")
    public List<ModelOptionView> modelsOf(@PathVariable String code) {
        currentUser.requireAdmin();
        return models.optionsOf(requireAgent(code)).stream().map(ModelOptionView::from).toList();
    }

    /**
     * 모델 목록 전체를 바꾼다.
     *
     * <p>받은 순서가 그대로 순위가 된다. 빈 목록은 거절한다. 모델이 하나도 없는 에이전트는 실행할 수
     * 없기 때문이다.
     */
    @PutMapping("/{code}/models")
    public List<ModelOptionView> replaceModels(
            @PathVariable String code, @Valid @RequestBody UpdateModelOptionsRequest request) {
        currentUser.requireAdmin();
        return models.replace(requireAgent(code), request.toOptions()).stream()
                .map(ModelOptionView::from)
                .toList();
    }

    /**
     * 주소를 바꾸는 요청이면 닿는지 본 뒤에 바꾼다.
     *
     * <p>비어 있거나 지금 값과 같으면 아무것도 하지 않는다. 다른 것만 고치는 요청이 주소를 지우거나
     * 쓸데없이 Hermes 를 부르지 않게 한다. 끝의 {@code /} 만 다른 것도 같은 값으로 본다.
     */
    private void applyApiBaseUrl(Agent agent, String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) return;
        String next = stripTrailingSlash(apiBaseUrl.strip());
        if (next.equals(agent.apiBaseUrl())) return;
        endpointProbe.requireReachable(next, agent.hermesProfile());
        agent.changeApiBaseUrl(next);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @PostMapping("/{code}/sync-model")
    public ModelSyncView syncModel(@PathVariable String code) {
        currentUser.requireAdmin();
        Agent agent = requireAgent(code);
        AgentModelSync.SyncResult result = modelSync.sync(agent);
        if (!result.read()) {
            throw new ApiException(ErrorCode.AGENT_MODEL_UNKNOWN, "could not read the agent model");
        }
        return new ModelSyncView(
                agent.code(), agent.model(), agent.modelSyncedAt(), result.changed(), result.providerRead());
    }

    private Agent requireAgent(String code) {
        return agents.findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));
    }

    private Long ownerId(AgentVisibility visibility, String ownerEmail) {
        if (visibility != AgentVisibility.PRIVATE) return null;
        if (ownerEmail == null || ownerEmail.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a private agent needs an owner");
        }
        return users.findByEmail(ownerEmail)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "no such family member"))
                .id();
    }
}
