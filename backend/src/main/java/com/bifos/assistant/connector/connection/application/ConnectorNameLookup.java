package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.agent.application.port.AgentConnectorNames;
import com.bifos.assistant.connector.connection.domain.ReadyBinding;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 에이전트 목록에 보일 플러그인 이름을 읽는다(docs/features/agent-team/design.md 「할 수 있는 일을 모으기」).
 *
 * <p>바인딩과 연결이 모두 {@code READY} 인 줄만 센다. 이름은 카탈로그 캐시의 manifest {@code title} 이다. 캐시를 쓰는 까닭은 이름이
 * 보관 시간만큼 늦어도 위험이 없고, 목록을 열 때마다 대시보드를 부르지 않기 위해서다. 카탈로그를 읽지 못하면 모든 이름을 비우고, manifest
 * 가 없는 플러그인만 뺀다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorNameLookup implements AgentConnectorNames {
    private final ConnectorBindingRepository bindings;
    private final ConnectorCatalogCache catalog;


    @Override
    public Map<Long, List<String>> readyConnectorNames(Collection<Long> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        List<ReadyBinding> usable = bindings.findUsableByAgentIds(agentIds, BindingStatus.READY, ConnectionStatus.READY);
        if (usable.isEmpty()) {
            return Map.of();
        }
        Map<String, Optional<String>> titles = new HashMap<>();
        Map<Long, TreeSet<String>> names = new TreeMap<>();
        try {
            for (ReadyBinding binding : usable) {
                Optional<String> title = titles.computeIfAbsent(
                    binding.connectorId(), id -> catalog.find(id).map(ConnectorManifest::title));
                title.ifPresent(value -> names.computeIfAbsent(binding.agentId(), key -> new TreeSet<>()).add(value));
            }
        } catch (RuntimeException ex) {
            log.warn("connector catalog unreadable for agent capabilities reason={}", ex.getClass().getSimpleName());
            return Map.of();
        }
        Map<Long, List<String>> result = new HashMap<>();
        names.forEach((agentId, values) -> result.put(agentId, List.copyOf(values)));
        return result;
    }
}
