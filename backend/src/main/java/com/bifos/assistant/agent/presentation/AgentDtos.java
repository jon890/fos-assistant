package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/**
 * 에이전트 화면과 관리 화면이 주고받는 모양이다.
 *
 * <p>컨트롤러는 경로와 권한만 맡고 오가는 모양은 여기 둔다. 같은 저장소의 {@code ChatDtos} 와
 * {@code MemoryDtos} 가 같은 규칙을 따른다.
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    public record AgentView(String code, String name, String model, String visibility) {
        static AgentView from(Agent agent) {
            return new AgentView(agent.code(), agent.name(), agent.model(), agent.visibility().name());
        }
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
            String ownerEmail,
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String flow) {}

    public record UpdateAgentRequest(
            @NotNull Boolean enabled,
            @NotNull AgentVisibility visibility,
            String ownerEmail) {}

    public record ModelSyncView(String code, String model, Instant modelSyncedAt, boolean changed) {}

    public record AdminAgentView(Long id, String code, String name, String hermesProfile,
            String apiBaseUrl, String provider, String model, Instant modelSyncedAt,
            String costMode, String credentialScope, String visibility, Long ownerUserId,
            boolean enabled, String flow) {
        static AdminAgentView from(Agent agent) {
            return new AdminAgentView(agent.id(), agent.code(), agent.name(), agent.hermesProfile(),
                    agent.apiBaseUrl(), agent.provider(), agent.model(), agent.modelSyncedAt(),
                    agent.costMode().name(), agent.credentialScope().name(), agent.visibility().name(),
                    agent.ownerUserId(), agent.enabled(), agent.flow());
        }
    }
}
