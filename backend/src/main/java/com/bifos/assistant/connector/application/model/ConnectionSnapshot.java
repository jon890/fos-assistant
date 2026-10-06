package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 자기 연결 하나의 상태다. 비밀 칸은 앞부분만 담는다.
 *
 * @param secretPrefixes 비밀 칸의 앞 4자. 값이 16자 미만인 칸은 없다
 * @param values 비밀이 아닌 칸의 값
 * @param bindings 이 연결이 붙은 에이전트들. 재시작 대기는 에이전트마다 여기 있다
 * @param undeclaredTools 마지막 확인에서 MCP 서버가 낸 도구 가운데 manifest 가 선언하지 않은 수
 */
public record ConnectionSnapshot(
        String connectorId,
        ConnectionStatus status,
        Map<String, String> secretPrefixes,
        Map<String, String> values,
        Instant checkedAt,
        List<BoundAgentSummary> bindings,
        int undeclaredTools) {

    public ConnectionSnapshot {
        bindings = List.copyOf(bindings);
    }

    public static ConnectionSnapshot from(ConnectorConnection connection, List<ConnectorBinding> bindings) {
        return new ConnectionSnapshot(
                connection.connectorId(),
                connection.status(),
                connection.fields().secretPrefixes(),
                connection.fields().values(),
                connection.checkedAt(),
                bindings.stream().map(BoundAgentSummary::from).toList(),
                connection.undeclaredTools());
    }
}
