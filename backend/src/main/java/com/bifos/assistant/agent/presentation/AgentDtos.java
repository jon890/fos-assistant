package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.PersonaSnapshot;
import com.bifos.assistant.agent.application.StarterService;
import com.bifos.assistant.agent.application.StarterSnapshot;
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
import jakarta.validation.constraints.Size;
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

    /**
     * 페르소나 본문의 상한이다.
     *
     * <p>매 실행의 고정 프롬프트에 그대로 들어가므로 길이가 곧 비용이다. 근거는 {@code
     * docs/code-architecture.md} 의 「페르소나」가 갖는다.
     */
    public static final int PERSONA_MAX_CHARS = 8000;

    private AgentDtos() {
    }

    /**
     * 에이전트의 성격 화면이 받는 것이다.
     *
     * @param body 지금 본문. 파일이 없으면 빈 문자열
     * @param bodyHash 그 본문의 지문. 저장할 때 그대로 돌려보낸다. 빈 본문에도 값이 있다
     * @param editable 지금 요청자가 고칠 수 있는가
     * @param maxChars 본문 상한. 화면이 남은 글자 수를 보인다
     */
    public record PersonaView(String body, String bodyHash, boolean editable, int maxChars) {
        static PersonaView from(PersonaSnapshot snapshot) {
            return new PersonaView(
                    snapshot.body(), snapshot.bodyHash(), snapshot.editable(), PERSONA_MAX_CHARS);
        }
    }

    /**
     * 성격을 쓰는 요청이다.
     *
     * @param baseHash 화면이 받아 간 본문의 지문. 비어 있는 것이 뜻을 갖는 값이라 필수로 두지 않는다.
     *     비었을 때의 규칙은 {@code PersonaService} 가 갖는다
     */
    public record WritePersonaRequest(
            @NotBlank @Size(max = PERSONA_MAX_CHARS) String body, String baseHash) {}

    /**
     * 에이전트의 소개와 추천 질문 화면이 받는 것이다.
     *
     * @param tagline 한 줄 소개. 비어 있으면 {@code null}
     * @param starterPrompts 추천 질문. 보이는 차례대로다
     * @param editable 지금 요청자가 고칠 수 있는가
     * @param maxPrompts 추천 질문의 최대 수. 화면이 입력 칸 수를 정한다
     */
    public record StartersView(
            String tagline, List<String> starterPrompts, boolean editable, int maxPrompts) {
        static StartersView from(StarterSnapshot snapshot) {
            return new StartersView(
                    snapshot.tagline(),
                    snapshot.starterPrompts(),
                    snapshot.editable(),
                    StarterService.MAX_STARTER_PROMPTS);
        }
    }

    /**
     * 소개와 추천 질문을 한꺼번에 쓰는 요청이다.
     *
     * <p>수와 길이에 요청 본문 검증을 걸지 않는다. 둘 다 앞뒤 공백과 빈 줄을 버린 뒤에 세야 하므로 그 판정은
     * {@code StarterService} 가 갖는다. 여기서 세면 공백이 붙은 200자 소개처럼 저장하면 상한 안인 값을
     * 거절한다. 목록 안의 {@code null} 줄도 거기서 빈 줄처럼 버린다.
     *
     * @param tagline 한 줄 소개. 비우면 소개를 지운다
     * @param starterPrompts 추천 질문 전체. 이 목록으로 통째로 바꾼다. 비우면 모두 지운다
     */
    public record WriteStartersRequest(String tagline, List<String> starterPrompts) {}

    /**
     * 사용자가 대화를 시작할 때 고르는 에이전트 한 줄.
     *
     * @param acceptsAttachments 이 에이전트의 대화에 사진을 붙일 수 있다. 화면이 이때만 사진 단추를
     *     둔다. 흐름 이름은 내보내지 않는다
     * @param tagline 새 대화 화면에 보일 한 줄 소개. 비어 있으면 {@code null}
     * @param starterPrompts 새 대화 화면에 보일 추천 질문. 보이는 차례대로다. 없으면 빈 목록
     */
    public record AgentView(
            String code,
            String name,
            String model,
            String visibility,
            boolean acceptsAttachments,
            String tagline,
            List<String> starterPrompts) {
        static AgentView from(Agent agent, List<String> starterPrompts) {
            return new AgentView(
                    agent.code(),
                    agent.name(),
                    agent.model(),
                    agent.visibility().name(),
                    agent.acceptsAttachments(),
                    agent.tagline(),
                    starterPrompts);
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
