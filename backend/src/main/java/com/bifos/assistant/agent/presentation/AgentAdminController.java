package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentModelSync;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/agents")
public class AgentAdminController {
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;
    private final HermesModelClient hermesModels;
    private final AgentModelSync modelSync;

    public AgentAdminController(AgentRepository agents, AppUserRepository users,
            CurrentUserProvider currentUser, HermesModelClient hermesModels,
            AgentModelSync modelSync) {
        this.agents = agents;
        this.users = users;
        this.currentUser = currentUser;
        this.hermesModels = hermesModels;
        this.modelSync = modelSync;
    }

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
        return AdminAgentView.from(agents.save(agent));
    }

    @GetMapping
    public List<AdminAgentView> list() {
        currentUser.requireAdmin();
        return agents.findAll().stream().map(AdminAgentView::from).toList();
    }

    @PatchMapping("/{code}")
    public AdminAgentView update(@PathVariable String code,
            @Valid @RequestBody UpdateAgentRequest request) {
        currentUser.requireAdmin();
        Agent agent = agents.findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));
        Long ownerId = request.visibility() == AgentVisibility.PRIVATE
                ? (request.ownerEmail() == null || request.ownerEmail().isBlank()
                        ? agent.ownerUserId()
                        : ownerId(request.visibility(), request.ownerEmail()))
                : null;
        if (request.visibility() == AgentVisibility.PRIVATE && ownerId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a private agent needs an owner");
        }
        agent.changeAccess(request.enabled(), request.visibility(), ownerId);
        return AdminAgentView.from(agents.save(agent));
    }

    @PostMapping("/{code}/sync-model")
    public ModelSyncView syncModel(@PathVariable String code) {
        currentUser.requireAdmin();
        Agent agent = agents.findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));
        AgentModelSync.SyncResult result = modelSync.sync(agent);
        if (!result.read()) {
            throw new ApiException(ErrorCode.AGENT_MODEL_UNKNOWN, "could not read the agent model");
        }
        return new ModelSyncView(agent.code(), agent.model(), agent.modelSyncedAt(), result.changed());
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

    public record CreateAgentRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String code,
            @NotBlank String name,
            @NotBlank String hermesProfile,
            @NotBlank String apiBaseUrl,
            @NotBlank String provider,
            @NotNull CostMode costMode,
            @NotNull CredentialScope credentialScope,
            @NotNull AgentVisibility visibility,
            String ownerEmail) {}

    public record UpdateAgentRequest(
            @NotNull Boolean enabled,
            @NotNull AgentVisibility visibility,
            String ownerEmail) {}

    public record ModelSyncView(String code, String model, Instant modelSyncedAt, boolean changed) {}

    public record AdminAgentView(Long id, String code, String name, String hermesProfile,
            String apiBaseUrl, String provider, String model, Instant modelSyncedAt,
            String costMode, String credentialScope, String visibility, Long ownerUserId,
            boolean enabled) {
        static AdminAgentView from(Agent agent) {
            return new AdminAgentView(agent.id(), agent.code(), agent.name(), agent.hermesProfile(),
                    agent.apiBaseUrl(), agent.provider(), agent.model(), agent.modelSyncedAt(),
                    agent.costMode().name(), agent.credentialScope().name(), agent.visibility().name(),
                    agent.ownerUserId(), agent.enabled());
        }
    }
}
