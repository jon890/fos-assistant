package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.connector.connection.application.dto.BoundAgentSummary;
import com.bifos.assistant.connector.connection.application.dto.ConnectorSummary;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.mcp.application.port.ConnectorListing;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@code connector_list} 가 돌려줄 플러그인 목록을 연결 화면의 카탈로그에서 만든다(스펙 service-concepts REQ5, DECISION22).
 *
 * <p>설명, 입력 칸, 도구 목록, 다른 에이전트의 바인딩은 넣지 않는다. 카탈로그에서 빠진 내 연결(쓸 수 없는 항목)도 넣지 않는다.
 * 링크의 {@code ?agent=} 는 모델이 아니라 여기서 정한다. 요청자가 주인인 비공개 에이전트에만 붙인다. 그룹 공개 에이전트에는 붙일 수 없어서다.
 */
@Service
@RequiredArgsConstructor
public class ConnectorListings implements ConnectorListing {
    static final String NONE = "none";
    static final String READY = "ready";
    static final String PENDING = "pending";

    private final ConnectorConnectionService connections;
    private final AgentService agents;


    @Override
    public List<Map<String, Object>> list(CurrentUser user, Long agentId) {
        Optional<Agent> agent = agentId == null ? Optional.empty() : agents.findById(agentId);
        String agentCode = agent.map(Agent::code).orElse(null);
        String linkAgent = agent.filter(value -> ownPrivate(user, value)).map(Agent::code).orElse(null);
        return connections.catalog(user).stream()
            .filter(ConnectorSummary::available)
            .map(summary -> item(summary, agentCode, linkAgent))
            .toList();
    }


    private static Map<String, Object> item(ConnectorSummary summary, String agentCode, String linkAgent) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", summary.id());
        item.put("title", summary.title());
        item.put("connected", summary.myStatus() == ConnectionStatus.READY);
        item.put("thisAgent", bindingState(summary.bindings(), agentCode));
        item.put("link", "/connections/" + summary.id() + (linkAgent == null ? "" : "?agent=" + linkAgent));
        return item;
    }


    /** 연결 화면의 「붙음」 과 「붙이는 중」 과 같은 나눔이다. 반영을 기다리는 바인딩은 아직 쓸 수 없다. */
    static String bindingState(List<BoundAgentSummary> bindings, String agentCode) {
        if (agentCode == null) {
            return NONE;
        }
        return bindings.stream()
            .filter(binding -> Objects.equals(binding.agentCode(), agentCode))
            .findFirst()
            .map(binding -> binding.status() == BindingStatus.READY && !binding.restartRequired() ? READY : PENDING)
            .orElse(NONE);
    }


    private static boolean ownPrivate(CurrentUser user, Agent agent) {
        return agent.visibility() == AgentVisibility.PRIVATE && Objects.equals(agent.ownerUserId(), user.id());
    }
}
