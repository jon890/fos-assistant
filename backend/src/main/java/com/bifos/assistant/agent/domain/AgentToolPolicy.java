package com.bifos.assistant.agent.domain;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 에이전트가 쓸 수 있는 toolset과 변경 권한을 한곳에서 판정한다. */
public final class AgentToolPolicy {

    public static final String MEMORY = "memory";
    public static final String MEMORY_MCP = "fos-assistant-memory";

    private static final Set<String> OWNER_TOOLSETS = Set.of(
            "web", "vision", "todo", "clarify", "skills", "tts", "delegation");
    private static final Set<String> ADMIN_TOOLSETS = Set.of(
            "terminal", "file", "code_execution", "browser", "computer_use", "cronjob", "image_gen",
            "video_gen", "homeassistant", "spotify", "discord", "session_search");
    // session_search 는 그 profile 의 모든 플랫폼 대화와, `profile` 인자로 다른 profile 의 대화까지 읽는다.
    private static final Set<String> PRIVATE_ONLY_TOOLSETS = Set.of(
            "terminal", "file", "code_execution", "browser", "computer_use", "session_search");
    private static final Set<String> CONFIGURABLE_TOOLSETS;

    static {
        LinkedHashSet<String> names = new LinkedHashSet<>(OWNER_TOOLSETS);
        names.addAll(ADMIN_TOOLSETS);
        CONFIGURABLE_TOOLSETS = Set.copyOf(names);
    }

    private AgentToolPolicy() {}

    public enum Tier {
        OWNER,
        ADMIN
    }

    public static boolean isKnown(String name) {
        return CONFIGURABLE_TOOLSETS.contains(name);
    }

    public static Tier tierOf(String name) {
        if (OWNER_TOOLSETS.contains(name)) return Tier.OWNER;
        if (ADMIN_TOOLSETS.contains(name)) return Tier.ADMIN;
        throw new IllegalArgumentException("unknown toolset");
    }

    public static boolean requiresPrivate(String name) {
        return PRIVATE_ONLY_TOOLSETS.contains(name);
    }

    public static boolean mayEdit(CurrentUser user, Agent agent, String name) {
        return tierOf(name) == Tier.OWNER
                ? (user.isAdmin() || java.util.Objects.equals(user.id(), agent.ownerUserId()))
                : user.isAdmin();
    }

    /** 요청자가 보낸 전체 목록을 검사하고 Hermes에 저장할 목록을 계산한다. */
    public static List<String> requestedForWrite(
            CurrentUser user, Agent agent, List<String> requested, List<String> currentlyEnabled) {
        if (requested == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "enabled toolsets are required");
        }
        LinkedHashSet<String> requestedSet = new LinkedHashSet<>();
        for (String name : requested) {
            if (name == null || name.isBlank() || MEMORY.equals(name) || MEMORY_MCP.equals(name) || !isKnown(name)) {
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
            if (mayEdit(user, agent, name)) result.add(name);
        }
        for (String name : ADMIN_TOOLSETS) {
            if (!mayEdit(user, agent, name) && current.contains(name)) result.add(name);
        }
        if (agent.visibility() == AgentVisibility.GROUP
                && result.stream().anyMatch(AgentToolPolicy::requiresPrivate)) {
            throw new ApiException(
                    ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE,
                    "shell and file toolsets require a private agent");
        }
        result.add(MEMORY_MCP);
        return List.copyOf(result);
    }

    public static boolean hasPrivateOnlyToolset(List<String> enabled) {
        return enabled.stream().anyMatch(AgentToolPolicy::requiresPrivate);
    }
}
