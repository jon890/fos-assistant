package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 설치의 일반 에이전트에서 켜진 도구와 그룹의 숨김 설정을 함께 읽는다. */
@Service
@RequiredArgsConstructor
public class ToolsetCatalogService {
    private final ToolsetVisibilityService visibility;
    private final HermesToolsetClient toolsets;
    private final AgentRepository agents;

    public List<ToolsetCatalogView> read(CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only admins can read the toolset catalog");
        }
        Set<String> hidden = visibility.hiddenFor(user.groupId());
        Map<String, ToolsetCatalogEntry> catalog = new LinkedHashMap<>();
        toolsets.readCatalog().stream()
                .filter(entry -> AgentToolPolicy.isKnown(entry.name()))
                .forEach(entry -> catalog.put(entry.name(), entry));
        // Hermes에서 사라진 도구도 숨김을 해제할 수 있도록 설정에 남은 이름을 보인다.
        hidden.stream().sorted().forEach(name -> catalog.putIfAbsent(name, new ToolsetCatalogEntry(name, name, "")));
        Map<Agent, List<String>> enabled = new LinkedHashMap<>();
        for (Agent agent : agents.findByDeletedAtIsNullAndConnectorManagedFalseOrderByCodeAsc()) {
            enabled.put(agent, toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()));
        }
        // 읽지 못한 profile을 0개로 세지 않는다. Hermes 오류는 그대로 전달해 다시 읽게 한다.
        return catalog.values().stream()
                .map(entry -> new ToolsetCatalogView(
                        entry.name(),
                        entry.label(),
                        entry.description(),
                        hidden.contains(entry.name()),
                        enabled.entrySet().stream()
                                .filter(row -> row.getValue().contains(entry.name()))
                                .map(row -> new EnabledToolsetAgent(
                                        row.getKey().code(), row.getKey().name()))
                                .toList()))
                .toList();
    }
}
