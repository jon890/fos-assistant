package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.infra.SkillStore;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 에이전트 toolset의 조회, 권한 판정, Hermes 설정 반영을 맡는다. */
@Service
@RequiredArgsConstructor
public class AgentToolService {

    public record ToolView(
            String name,
            String label,
            String description,
            AgentToolPolicy.Tier tier,
            boolean enabled,
            boolean editable,
            boolean requiresPrivate) {}

    public record ToolsetsView(List<ToolView> toolsets, List<String> unclassifiedEnabled) {}

    private final HermesToolsetClient toolsets;
    private final SkillStore skillStore;
    private final AgentService agents;
    private final AgentRepository agentRepository;

    public ToolsetsView read(CurrentUser user, Agent agent) {
        requireOwnerOrAdmin(user, agent);
        List<String> enabled = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        return response(user, agent, toolsets.readCatalog(), enabled);
    }

    public ToolsetsView write(CurrentUser user, Agent agent, List<String> requested) {
        requireOwnerOrAdmin(user, agent);
        // 올린 스킬은 skills toolset 으로만 읽힌다. 스킬을 둔 채 끄면 화면에 보이는 스킬이 돌지 않는다(ADR-034).
        if ((requested == null || !requested.contains(AgentToolPolicy.SKILLS))
                && skillStore.hasUploadedSkills(agent.hermesProfile())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "the skills toolset stays on while this agent has uploaded skills");
        }
        List<ToolsetCatalogEntry> catalog = toolsets.readCatalog();
        Map<String, ToolsetCatalogEntry> knownCatalog = catalogByName(catalog);
        if (requested != null && requested.stream().anyMatch(name -> !knownCatalog.containsKey(name))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "the requested toolset is not in the Hermes catalog");
        }
        List<String> current = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        List<String> desired = AgentToolPolicy.requestedForWrite(user, agent, requested, current);
        toolsets.writeApiServer(agent.hermesProfile(), desired);
        List<String> applied = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        List<String> desiredBuiltin = desired.stream()
                .filter(name -> !AgentToolPolicy.CONTROL_PLANE_MCP.equals(name))
                .toList();
        List<String> controlledApplied = applied.stream()
                .filter(name -> AgentToolPolicy.isKnown(name) || AgentToolPolicy.MEMORY.equals(name))
                .toList();
        if (controlledApplied.contains(AgentToolPolicy.MEMORY)
                || !new LinkedHashSet<>(controlledApplied).equals(new LinkedHashSet<>(desiredBuiltin))) {
            List<String> missing =
                    requested.stream().filter(name -> !applied.contains(name)).toList();
            throw new ApiException(
                    ErrorCode.AGENT_TOOLS_NOT_APPLIED, "Hermes did not apply the requested toolsets", missing);
        }
        return response(user, agent, catalog, applied);
    }

    /** 관리자가 다른 사람의 에이전트까지 도구 목록을 읽는다. 관리자인지는 부르는 쪽이 먼저 확인한다. */
    public ToolsetsView readAsAdmin(CurrentUser user, String code) {
        return read(user, requireAgent(code));
    }

    /**
     * 요청자가 읽을 수 있는 에이전트를 잠그고 도구를 바꾼다.
     *
     * <p>잠금 조회와 Hermes 설정 반영이 한 트랜잭션 안에서 돈다. 잠금은 트랜잭션이 끝날 때 풀린다.
     */
    @Transactional
    public ToolsetsView writeReadable(CurrentUser user, String code, List<String> enabled) {
        return write(user, agents.requireReadableForUpdate(user, code), enabled);
    }

    /**
     * 관리자가 다른 사람의 에이전트까지 잠그고 도구를 바꾼다. 관리자인지는 부르는 쪽이 먼저 확인한다.
     *
     * <p>잠금 조회와 Hermes 설정 반영이 한 트랜잭션 안에서 돈다.
     */
    @Transactional
    public ToolsetsView writeAsAdmin(CurrentUser user, String code, List<String> enabled) {
        return write(user, requireAgentForUpdate(code), enabled);
    }

    /** 관리자가 읽을 에이전트다. 지운 에이전트는 없는 에이전트와 같다. */
    private Agent requireAgent(String code) {
        Agent agent = agentRepository
                .findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        return agent;
    }

    /**
     * 관리자가 고칠 에이전트를 잠그고 읽는다. 지운 에이전트는 없는 에이전트와 같다.
     *
     * <p>지운 에이전트의 profile 은 이미 거둬졌을 수 있어 도구를 바꿀 곳이 없다.
     */
    private Agent requireAgentForUpdate(String code) {
        Agent agent = agentRepository
                .findByCodeForUpdate(code)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        return agent;
    }

    private static List<ToolView> views(
            CurrentUser user, Agent agent, List<ToolsetCatalogEntry> catalog, List<String> enabled) {
        return catalog.stream()
                .filter(entry -> AgentToolPolicy.isKnown(entry.name()))
                .map(entry -> new ToolView(
                        entry.name(),
                        entry.label(),
                        entry.description(),
                        AgentToolPolicy.tierOf(entry.name()),
                        enabled.contains(entry.name()),
                        AgentToolPolicy.mayEdit(user, agent, entry.name()),
                        AgentToolPolicy.requiresPrivate(entry.name())))
                .toList();
    }

    private static ToolsetsView response(
            CurrentUser user, Agent agent, List<ToolsetCatalogEntry> catalog, List<String> enabled) {
        List<String> unclassified = enabled.stream()
                .filter(name -> !AgentToolPolicy.isKnown(name))
                .filter(name -> !AgentToolPolicy.MEMORY.equals(name))
                .filter(name -> !AgentToolPolicy.CONTROL_PLANE_MCP.equals(name))
                .toList();
        return new ToolsetsView(views(user, agent, catalog, enabled), unclassified);
    }

    private static Map<String, ToolsetCatalogEntry> catalogByName(List<ToolsetCatalogEntry> catalog) {
        Map<String, ToolsetCatalogEntry> result = new HashMap<>();
        catalog.forEach(entry -> result.put(entry.name(), entry));
        return result;
    }

    private static void requireOwnerOrAdmin(CurrentUser user, Agent agent) {
        if (agent.connectorManaged()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "connector-managed agent toolsets cannot be changed here");
        }
        if (!user.isAdmin() && !Objects.equals(user.id(), agent.ownerUserId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only the agent owner can read or change toolsets");
        }
    }
}
