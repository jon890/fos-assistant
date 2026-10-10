package com.bifos.assistant.agent.application.toolset;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.ProfileSkillFiles;
import com.bifos.assistant.agent.application.model.AgentToolView;
import com.bifos.assistant.agent.application.model.AgentToolsetsView;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 에이전트 toolset의 조회, 권한 판정, Hermes 설정 반영을 맡는다. */
@Service
@RequiredArgsConstructor
public class AgentToolService {

    private final HermesToolsetClient toolsets;
    private final ProfileSkillFiles skillFiles;
    private final AgentService agents;
    private final AgentRepository agentRepository;
    private final AgentConnectorBindings connectorBindings;
    private final ToolsetVisibilityService visibility;

    /** 사진이 있는 일반 turn 제출 전에 기존 profile에도 원본 조회 도구를 자동 제공한다. */
    @Transactional
    public void ensureAttachmentInspection(Agent agent) {
        if (agent.connectorManaged() || !agent.acceptsAttachments()) {
            return;
        }
        agent = requireAgentForUpdate(agent.code());
        if (agent.connectorManaged() || !agent.acceptsAttachments()) {
            return;
        }
        List<String> enabled = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        if (enabled.contains(AgentToolPolicy.ATTACHMENT_INSPECTION)) {
            return;
        }
        Set<String> desired = new LinkedHashSet<>(enabled);
        // listener의 내장 도구 목록에는 MCP 서버 이름이 없다. 설정을 다시 쓸 때 정책의 고정 서버와 바인딩을 보존한다.
        desired.add(AgentToolPolicy.CONTROL_PLANE_MCP);
        desired.add(AgentToolPolicy.ATTACHMENT_INSPECTION);
        desired.addAll(connectorBindings.connectorServers(agent.id()));
        toolsets.writeApiServer(agent.hermesProfile(), List.copyOf(desired), agent.sandboxOwner());
        if (!toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())
                .contains(AgentToolPolicy.ATTACHMENT_INSPECTION)) {
            throw new ApiException(ErrorCode.AGENT_TOOLS_NOT_APPLIED, "original inspection tool was not applied");
        }
    }

    public AgentToolsetsView read(CurrentUser user, Agent agent) {
        return read(user, agent, false);
    }

    private AgentToolsetsView read(CurrentUser user, Agent agent, boolean adminView) {
        requireOwnerOrAdmin(user, agent);
        List<String> enabled = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        return response(
                user,
                agent,
                toolsets.readCatalog(),
                enabled,
                connectorBindings.connectorServers(agent.id()),
                adminView);
    }

    public AgentToolsetsView write(CurrentUser user, Agent agent, List<String> requested) {
        return write(user, agent, requested, false);
    }

    private AgentToolsetsView write(CurrentUser user, Agent agent, List<String> requested, boolean adminView) {
        requireOwnerOrAdmin(user, agent);
        List<ToolsetCatalogEntry> catalog = toolsets.readCatalog();
        Map<String, ToolsetCatalogEntry> knownCatalog = catalogByName(catalog);
        // 붙은 커넥터의 MCP 서버는 내장 toolset 카탈로그에 없다. 그 이름은 정책이 목록 끝에 늘 더한다(ADR-083).
        Set<String> connectorServers = connectorBindings.connectorServers(agent.id());
        if (requested != null
                && requested.stream()
                        .anyMatch(name -> name == null
                                || (!knownCatalog.containsKey(name) && !connectorServers.contains(name)))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "the requested toolset is not in the Hermes catalog");
        }
        List<String> current = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        Set<String> hidden = visibility.hiddenFor(user.groupId());
        if (!adminView && requested != null) {
            if (requested.stream().anyMatch(hidden::contains)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "hidden toolsets cannot be requested here");
            }
            // 일반 화면이 받지 않은 숨김 도구는 전체 목록 저장에서도 현재 상태를 보존한다.
            List<String> preserved = new ArrayList<>(requested);
            current.stream().filter(hidden::contains).forEach(preserved::add);
            requested = List.copyOf(preserved);
        }
        // 올린 스킬은 skills toolset 으로만 읽힌다. 숨김으로 보존한 skills도 검사에 포함한다(ADR-034).
        if ((requested == null || !requested.contains(AgentToolPolicy.SKILLS))
                && skillFiles.hasUploaded(agent.hermesProfile())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "the skills toolset stays on while this agent has uploaded skills");
        }
        List<String> desired = AgentToolPolicy.requestedForWrite(user, agent, requested, current, connectorServers);
        // 셸 도구가 켜지면 Hermes 가 스킬이 요청한 profile 의 환경 값과 파일을 실행 공간에 넣는다(ADR-086).
        if (AgentToolPolicy.hasSandboxToolset(desired)) {
            List<String> requesting = skillFiles.uploadedRequestingSecrets(agent.hermesProfile());
            if (!requesting.isEmpty()) {
                throw new ApiException(
                        ErrorCode.AGENT_SKILL_REQUESTS_SECRETS,
                        "uploaded skills request environment values or credential files: "
                                + String.join(", ", requesting));
            }
        }
        toolsets.writeApiServer(agent.hermesProfile(), desired, agent.sandboxOwner());
        List<String> applied = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        List<String> desiredBuiltin = desired.stream()
                .filter(name -> !AgentToolPolicy.CONTROL_PLANE_MCP.equals(name))
                .filter(name -> !AgentToolPolicy.ATTACHMENT_INSPECTION.equals(name))
                .filter(name -> !connectorServers.contains(name))
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
        return response(user, agent, catalog, applied, connectorServers, adminView);
    }

    /** 관리자가 다른 사람의 에이전트까지 도구 목록을 읽는다. 관리자인지는 부르는 쪽이 먼저 확인한다. */
    public AgentToolsetsView readAsAdmin(CurrentUser user, String code) {
        return read(user, requireAgent(code), true);
    }

    /**
     * 요청자가 읽을 수 있는 에이전트를 잠그고 도구를 바꾼다.
     *
     * <p>잠금 조회와 Hermes 설정 반영이 한 트랜잭션 안에서 돈다. 잠금은 트랜잭션이 끝날 때 풀린다.
     */
    @Transactional
    public AgentToolsetsView writeReadable(CurrentUser user, String code, List<String> enabled) {
        return write(user, agents.requireReadableForUpdate(user, code), enabled);
    }

    /**
     * 관리자가 다른 사람의 에이전트까지 잠그고 도구를 바꾼다. 관리자인지는 부르는 쪽이 먼저 확인한다.
     *
     * <p>잠금 조회와 Hermes 설정 반영이 한 트랜잭션 안에서 돈다.
     */
    @Transactional
    public AgentToolsetsView writeAsAdmin(CurrentUser user, String code, List<String> enabled) {
        return write(user, requireAgentForUpdate(code), enabled, true);
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

    private List<AgentToolView> views(
            CurrentUser user, Agent agent, List<ToolsetCatalogEntry> catalog, List<String> enabled, boolean adminView) {
        Set<String> hidden = visibility.hiddenFor(user.groupId());
        return catalog.stream()
                .filter(entry -> AgentToolPolicy.isKnown(entry.name()))
                .filter(entry -> adminView || !hidden.contains(entry.name()))
                .map(entry -> new AgentToolView(
                        entry.name(),
                        entry.label(),
                        entry.description(),
                        AgentToolPolicy.tierOf(entry.name()),
                        enabled.contains(entry.name()),
                        AgentToolPolicy.mayEdit(user, agent, entry.name()),
                        AgentToolPolicy.requiresPrivate(entry.name()),
                        hidden.contains(entry.name())))
                .toList();
    }

    /** 붙은 커넥터의 MCP 서버는 Control Plane 이 넣은 이름이라 분류하지 못한 도구로 알리지 않는다. */
    private AgentToolsetsView response(
            CurrentUser user,
            Agent agent,
            List<ToolsetCatalogEntry> catalog,
            List<String> enabled,
            Set<String> connectorServers,
            boolean adminView) {
        List<String> unclassified = enabled.stream()
                .filter(name -> !AgentToolPolicy.isKnown(name))
                .filter(name -> !AgentToolPolicy.ATTACHMENT_INSPECTION.equals(name))
                .filter(name -> !AgentToolPolicy.MEMORY.equals(name))
                .filter(name -> !AgentToolPolicy.CONTROL_PLANE_MCP.equals(name))
                .filter(name -> !connectorServers.contains(name))
                .toList();
        return new AgentToolsetsView(
                views(user, agent, catalog, enabled, adminView),
                unclassified,
                AgentToolPolicy.hasShellOrFileToolset(enabled),
                enabled.contains(AgentToolPolicy.SKILLS));
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
