package com.bifos.assistant.agent.admin.presentation;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.presentation.AgentDtos.AgentView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자가 에이전트를 등록하고 고칠 때 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AgentAdminDtos {

    public record CreateAgentRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}")
            String code,

            @NotBlank String name,
            @NotBlank String hermesProfile,
            @NotBlank String apiBaseUrl,
            @NotNull CostMode costMode,
            @NotNull CredentialScope credentialScope,
            @NotNull AgentVisibility visibility,
            String ownerEmail,
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String flow) {}

    /**
     * 에이전트의 접근 범위와 Hermes 주소를 고치는 요청이다.
     *
     * @param apiBaseUrl 새 Hermes API 주소. 비어 있으면 지금 값을 그대로 둔다. 다른 것만 고치는 요청이
     *     주소를 지우면 안 되기 때문이다
     * @param proactiveCheckWritesAllowed 「먼저 살펴보기에 쓰기 도구 허용」. 비어 있으면 지금 값을 그대로 둔다. 같은
     *     까닭이다(ADR-082)
     */
    public record UpdateAgentRequest(
            @NotNull Boolean enabled,
            @NotNull AgentVisibility visibility,
            String ownerEmail,
            String apiBaseUrl,
            Boolean proactiveCheckWritesAllowed) {}

    /**
     * 관리 화면이 보는 에이전트 한 줄.
     *
     * <p>모델 칸을 두지 않는다. 에이전트 기본 모델은 {@code chat} 의 관리자 경로가 따로 돌려준다(ADR-054).
     *
     * @param proactiveCheckWritesAllowed 「먼저 살펴보기에 쓰기 도구 허용」. 관리자 전용 값이라 사용자 응답
     *     {@link AgentView} 에는 싣지 않는다(ADR-063, ADR-082)
     */
    public record AdminAgentView(
            Long id,
            String code,
            String name,
            String hermesProfile,
            String apiBaseUrl,
            String costMode,
            String credentialScope,
            String visibility,
            Long ownerUserId,
            boolean enabled,
            String flow,
            boolean connectorManaged,
            boolean proactiveCheckWritesAllowed) {
        static AdminAgentView from(Agent agent) {
            return new AdminAgentView(
                    agent.id(),
                    agent.code(),
                    agent.name(),
                    agent.hermesProfile(),
                    agent.apiBaseUrl(),
                    agent.costMode().name(),
                    agent.credentialScope().name(),
                    agent.visibility().name(),
                    agent.ownerUserId(),
                    agent.enabled(),
                    agent.flow(),
                    agent.connectorManaged(),
                    agent.proactiveCheckWritesAllowed());
        }
    }
}
