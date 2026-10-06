package com.bifos.assistant.connector.application;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 에이전트에 붙은 연결을 바인딩 행으로만 읽는다(ADR-083).
 *
 * <p>서버 이름은 붙일 때 바인딩 행에 적어 둔 것을 쓰고 카탈로그를 부르지 않는다. 대화와 도구 저장이 turn 마다 대시보드를
 * 기다리지 않고, 운영자가 카탈로그에서 커넥터를 빼도 그 profile 에 설치된 서버 이름을 잃지 않는다. 바인딩 서비스와 다른
 * bean 으로 둔다. 이 조회를 쓰는 {@code agent} 의 서비스를 바인딩 서비스가 거꾸로 부르지 않게 하기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class ConnectorBindingLookup implements AgentConnectorBindings {
    /** Hermes 가 MCP 도구의 등록 이름에서 바꾸는 글자다. {@code docs/hermes/connector-policy.md} 의 「MCP 도구의 등록 이름」 과 같다. */
    private static final String OUTSIDE = "[^A-Za-z0-9_]";

    /** 등록 이름이 길면 Hermes 가 이 길이까지만 남기고 해시를 붙인다. 앞부분은 이보다 길면 맞지 않는다. */
    private static final int KEPT_CHARS = 55;

    private final ConnectorBindingRepository bindings;

    @Override
    public boolean hasBindings(Long agentId) {
        return bindings.existsByAgentId(agentId);
    }

    @Override
    public Set<String> connectorServers(Long agentId) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(bindings.findByAgentId(agentId).stream()
                .map(ConnectorBinding::mcpServer)
                .filter(Objects::nonNull)
                .toList()));
    }

    @Override
    public Set<String> connectorToolPrefixes(Long agentId) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(connectorServers(agentId).stream()
                .map(ConnectorBindingLookup::toolPrefix)
                .toList()));
    }

    /**
     * 그 서버 도구의 등록 이름이 늘 시작하는 글이다.
     *
     * <p>등록 이름은 {@code mcp__<서버>__<도구>} 이고 64자를 넘으면 앞 55자만 남긴다. 그래서 앞부분도 55자까지만 쓴다.
     */
    private static String toolPrefix(String server) {
        String prefix = "mcp__" + server.replaceAll(OUTSIDE, "_") + "__";
        return prefix.length() > KEPT_CHARS ? prefix.substring(0, KEPT_CHARS) : prefix;
    }
}
