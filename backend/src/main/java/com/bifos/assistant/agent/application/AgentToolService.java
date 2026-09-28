package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 에이전트 toolset의 조회, 권한 판정, Hermes 설정 반영을 맡는다. */
@Service
@RequiredArgsConstructor
public class AgentToolService {

    public record ToolView(
            String name, String label, String description, AgentToolPolicy.Tier tier,
            boolean enabled, boolean editable, boolean requiresPrivate) {}
    public record ToolsetsView(List<ToolView> toolsets, List<String> unclassifiedEnabled) {}

    private final HermesToolsetClient toolsets;

    public ToolsetsView read(CurrentUser user, Agent agent) {
        requireOwnerOrAdmin(user, agent);
        List<String> enabled = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        return response(user, agent, toolsets.readCatalog(), enabled);
    }

    public ToolsetsView write(CurrentUser user, Agent agent, List<String> requested) {
        requireOwnerOrAdmin(user, agent);
        List<ToolsetCatalogEntry> catalog = toolsets.readCatalog();
        Map<String, ToolsetCatalogEntry> knownCatalog = catalogByName(catalog);
        if (requested != null && requested.stream().anyMatch(name -> !knownCatalog.containsKey(name))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "the requested toolset is not in the Hermes catalog");
        }
        List<String> current = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        List<String> desired = AgentToolPolicy.requestedForWrite(user, agent, requested, current);
        toolsets.writeApiServer(agent.hermesProfile(), desired);
        List<String> applied = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        if (!new LinkedHashSet<>(applied).equals(new LinkedHashSet<>(desired))) {
            List<String> missing = requested.stream().filter(name -> !applied.contains(name)).toList();
            throw new ApiException(
                    ErrorCode.AGENT_TOOLS_NOT_APPLIED, "Hermes did not apply the requested toolsets", missing);
        }
        return response(user, agent, catalog, applied);
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
                .filter(name -> !AgentToolPolicy.MEMORY_MCP.equals(name))
                .toList();
        return new ToolsetsView(views(user, agent, catalog, enabled), unclassified);
    }

    private static Map<String, ToolsetCatalogEntry> catalogByName(List<ToolsetCatalogEntry> catalog) {
        Map<String, ToolsetCatalogEntry> result = new HashMap<>();
        catalog.forEach(entry -> result.put(entry.name(), entry));
        return result;
    }

    private static void requireOwnerOrAdmin(CurrentUser user, Agent agent) {
        if (!user.isAdmin() && !Objects.equals(user.id(), agent.ownerUserId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only the agent owner can read or change toolsets");
        }
    }
}
