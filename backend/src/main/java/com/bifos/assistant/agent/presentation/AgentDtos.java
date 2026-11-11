package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.dto.PersonaSnapshot;
import com.bifos.assistant.agent.application.dto.AgentToolView;
import com.bifos.assistant.agent.application.dto.AgentCapabilities;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.agent.domain.type.AgentColor;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
     * 비었을 때의 규칙은 {@code PersonaService} 가 갖는다
     */
    public record WritePersonaRequest(
        @NotBlank @Size(max = PERSONA_MAX_CHARS) String body, String baseHash) {
    }

    /**
     * 사용자가 대화를 시작할 때 고르는 에이전트 한 줄.
     *
     * @param acceptsAttachments 이 에이전트의 대화에 사진을 붙일 수 있다. 화면이 이때만 사진 단추를
     * 둔다
     * @param editable 요청자가 이 에이전트를 관리할 수 있다. 주인과 {@code ADMIN} 이다. 화면이 공개와 삭제를
     * 보일지 이 값으로 정한다
     * @param ownedByMe 요청자가 이 에이전트의 주인이다
     * @param description 한 줄 소개. 없으면 null
     * @param icon {@code AgentIntroduction.ICONS} 의 이모지. 없으면 null
     * @param color {@code AgentColor} 의 이름. 없으면 null
     * @param capabilities 할 수 있는 일
     */
    public record AgentView(
        String code,
        String name,
        String visibility,
        boolean acceptsAttachments,
        boolean editable,
        boolean ownedByMe,
        String description,
        String icon,
        String color,
        CapabilitiesView capabilities) {
        static AgentView from(Agent agent, boolean editable, boolean ownedByMe, AgentCapabilities capabilities) {
            return new AgentView(
                agent.code(),
                agent.name(),
                agent.visibility().name(),
                agent.acceptsAttachments(),
                editable,
                ownedByMe,
                agent.description(),
                agent.icon(),
                agent.color() == null ? null : agent.color().name(),
                new CapabilitiesView(capabilities.connectors(), capabilities.skills()));
        }
    }

    /**
     * 에이전트가 할 수 있는 일이다. 둘 다 이름순이다.
     *
     * @param connectors 연결과 바인딩이 모두 준비된 플러그인의 이름
     * @param skills 올린 스킬의 이름
     */
    public record CapabilitiesView(List<String> connectors, List<String> skills) {
    }

    /**
     * 이름과 소개, 아이콘, 색을 바꾸는 요청이다. 네 칸을 모두 보낸다. 이름 밖의 null 이나 빈 글은 비우기다.
     *
     * <p>길이와 목록 판정은 {@code AgentIntroduction.of} 가 한다. 앞뒤 공백을 뗀 뒤에 세야 하므로 요청 본문 검증을 걸지 않는다.
     */
    public record ChangeIntroductionRequest(String name, String description, String icon, AgentColor color) {
        AgentIntroduction toIntroduction() {
            return AgentIntroduction.of(name, description, icon, color);
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
    public record CreateOwnAgentRequest(String name, AgentVisibility visibility) {
    }

    /** 에이전트의 공개 범위를 바꾸는 요청이다. */
    public record ChangeVisibilityRequest(@NotNull AgentVisibility visibility) {
    }

    /** 에이전트 API 실행에 켤 toolset 전체다. */
    public record UpdateToolsetsRequest(@NotNull List<@NotBlank String> enabled) {
    }

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
        boolean skillsEnabled) {
    }
}
