package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.PersonaSnapshot;
import com.bifos.assistant.agent.application.model.AgentToolView;
import com.bifos.assistant.agent.application.model.ToolsetCatalogView;
import com.bifos.assistant.agent.application.model.ToolsetRequestView;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 에이전트 선택과 도구 화면이 주고받는 모양이다.
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
     * docs/features/agent-skill.md} 의 「페르소나」가 갖는다.
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
     * @param runsTasks 예약 작업을 돌릴 수 있다. 흐름이 붙은 에이전트는 거짓이다. 화면이 작업의 에이전트 목록을 이 값으로 거른다
     */
    public record AgentView(
            String code,
            String name,
            String visibility,
            boolean acceptsAttachments,
            boolean editable,
            boolean ownedByMe,
            boolean connectorManaged,
            boolean runsTasks) {
        static AgentView from(Agent agent, boolean editable, boolean ownedByMe, boolean runsTasks) {
            return new AgentView(
                    agent.code(),
                    agent.name(),
                    agent.visibility().name(),
                    agent.acceptsAttachments(),
                    editable,
                    ownedByMe,
                    agent.connectorManaged(),
                    runsTasks);
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
            boolean requiresPrivate,
            boolean hidden) {
        static ToolsetView from(AgentToolView source) {
            return new ToolsetView(
                    source.name(),
                    source.label(),
                    source.description(),
                    source.tier().name(),
                    source.enabled(),
                    source.editable(),
                    source.requiresPrivate(),
                    source.hidden());
        }
    }

    public record ToolsetsView(
            List<ToolsetView> toolsets,
            List<String> unclassifiedEnabled,
            boolean shellOrFileEnabled,
            boolean skillsEnabled) {}

    public record HiddenToolsetsRequest(@NotNull List<@NotBlank String> hidden) {}

    public record RequestToolset(@NotBlank @Size(max = 64) String toolset) {}

    public record DecideToolsetRequest(
            @NotNull Boolean approve, @Size(max = 200) String reason) {}

    public record ToolsetRequestResponse(
            UUID id,
            String agentCode,
            String agentName,
            boolean agentDeleted,
            String requesterName,
            String toolset,
            String status,
            String reason,
            Instant requestedAt,
            Instant decidedAt) {
        static ToolsetRequestResponse from(ToolsetRequestView row) {
            return new ToolsetRequestResponse(
                    row.id(),
                    row.agentCode(),
                    row.agentName(),
                    row.agentDeleted(),
                    row.requesterName(),
                    row.toolset(),
                    row.status().name(),
                    row.reason(),
                    row.requestedAt(),
                    row.decidedAt());
        }
    }

    public record EnabledToolsetAgentView(String code, String name) {}

    public record CatalogToolsetView(
            String name,
            String label,
            String description,
            boolean hidden,
            List<EnabledToolsetAgentView> enabledAgents) {
        static CatalogToolsetView from(ToolsetCatalogView source) {
            return new CatalogToolsetView(
                    source.name(),
                    source.label(),
                    source.description(),
                    source.hidden(),
                    source.enabledAgents().stream()
                            .map(agent -> new EnabledToolsetAgentView(agent.code(), agent.name()))
                            .toList());
        }
    }


}
