package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentModelOption;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.domain.ProviderState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    /**
     * 에이전트의 접근 범위와 Hermes 주소를 고치는 요청이다.
     *
     * @param apiBaseUrl 새 Hermes API 주소. 비어 있으면 지금 값을 그대로 둔다. 다른 것만 고치는 요청이
     *     주소를 지우면 안 되기 때문이다
     */
    public record UpdateAgentRequest(
            @NotNull Boolean enabled,
            @NotNull AgentVisibility visibility,
            String ownerEmail,
            String apiBaseUrl) {}

    /**
     * @param providerRead Hermes 가 provider 도 함께 줬는가. 거짓이면 provider 는 그대로 두었다
     */
    public record ModelSyncView(
            String code, String model, Instant modelSyncedAt, boolean changed, boolean providerRead) {}

    /** 모델 목록의 한 줄. {@code rank} 는 1부터 세고 1이 1순위다. */
    public record ModelOptionView(int rank, String provider, String model) {
        static ModelOptionView from(AgentModelOption option) {
            return new ModelOptionView(option.rank(), option.provider(), option.model());
        }
    }

    /**
     * 목록 전체를 받아 통째로 바꾼다. 한 줄씩 고치는 경로를 두지 않는다.
     *
     * <p>순서가 뜻을 갖는 목록이라 부분 수정은 순서가 어긋날 자리를 만든다. 빈 목록은 거절한다. 모델이
     * 하나도 없는 에이전트는 실행할 수 없다.
     */
    public record UpdateModelOptionsRequest(@NotEmpty @Valid List<ModelOptionRequest> models) {

        public List<ModelOption> toOptions() {
            return models.stream().map(it -> new ModelOption(it.provider(), it.model())).toList();
        }
    }

    public record ModelOptionRequest(@NotBlank String provider, @NotBlank String model) {}

    /**
     * 지금 막혀 있는 provider 한 줄.
     *
     * @param remainingSeconds 풀리기까지 남은 초. 화면이 「12분 남음」 으로 적는 데 쓴다
     */
    public record BlockedProviderView(String provider, Instant blockedUntil, long remainingSeconds) {
        static BlockedProviderView from(ProviderState state, Instant now) {
            long remaining = Math.max(0, Duration.between(now, state.blockedUntil()).toSeconds());
            return new BlockedProviderView(state.provider(), state.blockedUntil(), remaining);
        }
    }

    /**
     * 관리 화면이 보는 에이전트 한 줄.
     *
     * <p>{@code provider} 와 {@code model} 은 지난 실행 기록을 읽기 위해 남겨 둔 값이다. 새 실행이
     * 쓰는 것은 {@code models} 의 1순위다.
     *
     * @param models 이 에이전트가 쓸 모델 목록. 순위 순서다
     */
    public record AdminAgentView(Long id, String code, String name, String hermesProfile,
            String apiBaseUrl, String provider, String model, Instant modelSyncedAt,
            String costMode, String credentialScope, String visibility, Long ownerUserId,
            boolean enabled, String flow, List<ModelOptionView> models) {
        static AdminAgentView from(Agent agent, List<AgentModelOption> models) {
            return new AdminAgentView(agent.id(), agent.code(), agent.name(), agent.hermesProfile(),
                    agent.apiBaseUrl(), agent.provider(), agent.model(), agent.modelSyncedAt(),
                    agent.costMode().name(), agent.credentialScope().name(), agent.visibility().name(),
                    agent.ownerUserId(), agent.enabled(), agent.flow(),
                    models.stream().map(ModelOptionView::from).toList());
        }
    }
}
