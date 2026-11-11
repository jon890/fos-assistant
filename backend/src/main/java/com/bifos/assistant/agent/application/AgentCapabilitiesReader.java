package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.application.dto.AgentCapabilities;
import com.bifos.assistant.agent.application.port.AgentConnectorNames;
import com.bifos.assistant.agent.application.port.ProfileSkillFiles;
import com.bifos.assistant.agent.domain.Agent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 에이전트 목록의 할 수 있는 일을 한 번에 모은다(docs/features/agent-team/design.md 「할 수 있는 일을 모으기」).
 *
 * <p>에이전트 목록 화면과 위임 도구 {@code agent_list} 가 같은 값을 쓴다. 플러그인은 바인딩을 한 번에 읽고, 스킬은 에이전트마다
 * 디렉터리 이름만 읽는다. Hermes 를 부르지 않는다. 어느 쪽을 읽지 못해도 예외를 올리지 않고 그 칸을 비운다.
 */
@Service
@RequiredArgsConstructor
public class AgentCapabilitiesReader {
    private final AgentConnectorNames connectorNames;
    private final ProfileSkillFiles skillFiles;


    /** 에이전트 번호마다의 할 수 있는 일이다. 목록의 모든 에이전트가 들어 있다. */
    public Map<Long, AgentCapabilities> read(List<Agent> agents) {
        if (agents.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> connectors = connectorNames.readyConnectorNames(
            agents.stream().map(Agent::id).toList());
        Map<Long, AgentCapabilities> result = new HashMap<>();
        for (Agent agent : agents) {
            result.put(agent.id(), new AgentCapabilities(
                connectors.getOrDefault(agent.id(), List.of()),
                skillFiles.uploadedNames(agent.hermesProfile())));
        }
        return result;
    }
}
