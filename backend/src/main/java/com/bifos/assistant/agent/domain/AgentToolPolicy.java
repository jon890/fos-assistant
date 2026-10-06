package com.bifos.assistant.agent.domain;

import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 에이전트가 쓸 수 있는 toolset과 변경 권한을 한곳에서 판정한다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AgentToolPolicy {

    public static final String MEMORY = "memory";
    /** 올린 스킬을 모델이 읽는 toolset 이다. 올린 스킬이 있는 동안은 끄지 못한다(ADR-034). */
    public static final String SKILLS = "skills";
    /** 사진을 읽는 toolset 이다. 사진을 받는 커넥터는 이것을 함께 선언한다(ADR-044). */
    public static final String VISION = "vision";
    /** Control Plane 이 여는 MCP 서버의 Hermes 등록 이름이며, 도구 저장 때 허용 목록에 늘 남긴다. */
    public static final String CONTROL_PLANE_MCP = "fos-assistant";

    private static final Set<String> OWNER_TOOLSETS =
            Set.of("web", "vision", "todo", "clarify", "skills", "tts", "delegation");
    private static final Set<String> ADMIN_TOOLSETS = Set.of(
            "terminal",
            "file",
            "code_execution",
            "browser",
            "computer_use",
            "cronjob",
            "image_gen",
            "video_gen",
            "homeassistant",
            "spotify",
            "discord",
            "session_search");
    // session_search 는 그 profile 의 모든 플랫폼 대화와, `profile` 인자로 다른 profile 의 대화까지 읽는다.
    private static final Set<String> PRIVATE_ONLY_TOOLSETS = Set.of(
            "terminal",
            "file",
            "code_execution",
            "vision",
            "image_gen",
            "video_gen",
            "browser",
            "computer_use",
            "session_search");
    /** 사용자별 실행 공간에서 도는 toolset 이다. 켜진 동안은 주인을 바꾸지 못한다(ADR-086). */
    private static final Set<String> SANDBOX_TOOLSETS =
            Set.of("terminal", "file", "code_execution", "vision", "image_gen", "video_gen");
    /** 커넥터 manifest 가 연결용 에이전트에 열 수 있는 내장 toolset 이다. 읽기 전용 이미지 도구뿐이다(ADR-044). */
    private static final Set<String> CONNECTOR_TOOLSETS = Set.of(AgentToolPolicy.VISION);

    private static final Set<String> CONFIGURABLE_TOOLSETS;

    static {
        LinkedHashSet<String> names = new LinkedHashSet<>(OWNER_TOOLSETS);
        names.addAll(ADMIN_TOOLSETS);
        CONFIGURABLE_TOOLSETS = Set.copyOf(names);
    }

    public enum Tier {
        OWNER,
        ADMIN
    }

    public static boolean isKnown(String name) {
        return CONFIGURABLE_TOOLSETS.contains(name);
    }

    public static Tier tierOf(String name) {
        if (OWNER_TOOLSETS.contains(name)) {
            return Tier.OWNER;
        }
        if (ADMIN_TOOLSETS.contains(name)) {
            return Tier.ADMIN;
        }
        throw new IllegalArgumentException("unknown toolset");
    }

    public static boolean requiresPrivate(String name) {
        return PRIVATE_ONLY_TOOLSETS.contains(name);
    }

    public static boolean mayEdit(CurrentUser user, Agent agent, String name) {
        return tierOf(name) == Tier.OWNER
                ? (user.isAdmin() || Objects.equals(user.id(), agent.ownerUserId()))
                : user.isAdmin();
    }

    /**
     * 요청자가 보낸 전체 목록을 검사하고 Hermes에 저장할 목록을 계산한다.
     *
     * <p>그 에이전트에 붙은 커넥터의 MCP 서버 이름은 목록 끝에 늘 더한다(ADR-083). 대시보드는 그 이름이 빠진 목록을 거절한다.
     * 요청에 그 이름이 들어와도 모르는 이름으로 거절하지 않는다. 화면이 읽은 목록을 그대로 돌려보낼 수 있다.
     *
     * @param connectorServers 그 에이전트에 붙은 커넥터의 MCP 서버 이름
     */
    public static List<String> requestedForWrite(
            CurrentUser user,
            Agent agent,
            List<String> requested,
            List<String> currentlyEnabled,
            Set<String> connectorServers) {
        if (requested == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "enabled toolsets are required");
        }
        LinkedHashSet<String> requestedSet = new LinkedHashSet<>();
        for (String name : requested) {
            if (name != null && connectorServers.contains(name)) {
                continue;
            }
            if (name == null
                    || name.isBlank()
                    || MEMORY.equals(name)
                    || CONTROL_PLANE_MCP.equals(name)
                    || !isKnown(name)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "the requested toolset is not allowed");
            }
            if (!requestedSet.add(name)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "a toolset was requested more than once");
            }
        }

        Set<String> current = Set.copyOf(currentlyEnabled);
        for (String name : ADMIN_TOOLSETS) {
            if (!mayEdit(user, agent, name) && requestedSet.contains(name) != current.contains(name)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "this toolset requires an admin");
            }
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String name : requestedSet) {
            if (mayEdit(user, agent, name)) {
                result.add(name);
            }
        }
        for (String name : ADMIN_TOOLSETS) {
            if (!mayEdit(user, agent, name) && current.contains(name)) {
                result.add(name);
            }
        }
        if (agent.visibility() == AgentVisibility.GROUP && result.stream().anyMatch(AgentToolPolicy::requiresPrivate)) {
            throw new ApiException(
                    ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE, "shell and file toolsets require a private agent");
        }
        result.add(CONTROL_PLANE_MCP);
        result.addAll(connectorServers);
        return List.copyOf(result);
    }

    /** 커넥터가 선언한 toolset 이 모두 manifest 로 열 수 있는 것인가. */
    public static boolean allowedForConnector(List<String> declared) {
        return CONNECTOR_TOOLSETS.containsAll(declared);
    }

    public static boolean hasPrivateOnlyToolset(List<String> enabled) {
        return enabled.stream().anyMatch(AgentToolPolicy::requiresPrivate);
    }

    /** 사용자별 실행 공간에서 도는 셸·파일·사진 toolset 이 하나라도 켜졌는가(ADR-089). */
    public static boolean hasSandboxToolset(List<String> enabled) {
        return enabled.stream().anyMatch(SANDBOX_TOOLSETS::contains);
    }
}
