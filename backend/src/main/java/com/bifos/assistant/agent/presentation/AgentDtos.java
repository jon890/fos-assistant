package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.AgentToolView;
import com.bifos.assistant.agent.application.PersonaSnapshot;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 에이전트 화면과 관리 화면이 주고받는 모양이다.
 *
 * <p>컨트롤러는 경로와 권한만 맡고 오가는 모양은 여기 둔다. 같은 저장소의 {@code ChatDtos} 와
 * {@code MemoryDtos} 가 같은 규칙을 따른다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AgentDtos {

    /**
     * 페르소나 본문의 상한이다.
     *
     * <p>매 실행의 고정 프롬프트에 그대로 들어가므로 길이가 곧 비용이다. 근거는 {@code
     * docs/backend/agent.md} 의 「페르소나」가 갖는다.
     */
    public static final int PERSONA_MAX_CHARS = 8000;

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
            return new PersonaView(snapshot.body(), snapshot.bodyHash(), snapshot.editable(), PERSONA_MAX_CHARS);
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
     * 사용자가 대화를 시작할 때 고르는 에이전트 한 줄.
     *
     * @param acceptsAttachments 이 에이전트의 대화에 사진을 붙일 수 있다. 화면이 이때만 사진 단추를
     *     둔다. 흐름 이름은 내보내지 않는다
     * @param editable 요청자가 이 에이전트를 관리할 수 있다. 주인과 {@code ADMIN} 이다. 화면이 공개와 삭제를
     *     보일지 이 값으로 정한다
     * @param ownedByMe 요청자가 이 에이전트의 주인이다
     * @param connectorManaged 커넥터 연결이 만든 에이전트다. 화면이 도구와 스킬 편집을 그리지 않는다
     */
    public record AgentView(
            String code,
            String name,
            String visibility,
            boolean acceptsAttachments,
            boolean editable,
            boolean ownedByMe,
            boolean connectorManaged) {
        static AgentView from(Agent agent, boolean editable, boolean ownedByMe) {
            return new AgentView(
                    agent.code(),
                    agent.name(),
                    agent.visibility().name(),
                    agent.acceptsAttachments(),
                    editable,
                    ownedByMe,
                    agent.connectorManaged());
        }
    }

    /**
     * 사용자가 자기 에이전트를 만드는 요청이다.
     *
     * <p>이름의 길이는 앞뒤 공백을 뗀 뒤에 세야 하므로 요청 본문 검증을 걸지 않고 {@code
     * AgentLifecycleService} 가 판정한다.
     *
     * @param name 보이는 이름. 한글이어도 되고 다른 에이전트와 겹쳐도 된다
     * @param visibility 비어 있으면 {@code PRIVATE}
     */
    public record CreateOwnAgentRequest(String name, AgentVisibility visibility) {}

    /** 에이전트의 공개 범위를 바꾸는 요청이다. */
    public record ChangeVisibilityRequest(@NotNull AgentVisibility visibility) {}

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

    /** 에이전트 API 실행에 켤 toolset 전체다. */
    public record UpdateToolsetsRequest(@NotNull List<@NotBlank String> enabled) {}

    /** 도구 선택 화면이 보여 주는 toolset 한 줄이다. */
    public record ToolsetView(
            String name,
            String label,
            String description,
            String tier,
            boolean enabled,
            boolean editable,
            boolean requiresPrivate) {
        static ToolsetView from(AgentToolView source) {
            return new ToolsetView(
                    source.name(),
                    source.label(),
                    source.description(),
                    source.tier().name(),
                    source.enabled(),
                    source.editable(),
                    source.requiresPrivate());
        }
    }

    public record ToolsetsView(List<ToolsetView> toolsets, List<String> unclassifiedEnabled) {}

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
