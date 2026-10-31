package com.bifos.assistant.connector.connection.application.dto;

import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.ListedTools;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
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
 * @param listedTools 표준 플러그인 연결의 마지막 확인에서 MCP 서버가 낸 도구 수. 표준 플러그인이 아니거나 확인하기 전이면 null
 * @param readOnlyTools 그 가운데 읽기 표시({@code readOnlyHint})가 참인 도구 수. {@code listedTools} 와 함께 null 이다
 */
public record ConnectionSnapshot(
    String connectorId,
    ConnectionStatus status,
    Map<String, String> secretPrefixes,
    Map<String, String> values,
    Instant checkedAt,
    List<BoundAgentSummary> bindings,
    int undeclaredTools,
    Integer listedTools,
    Integer readOnlyTools) {

    public ConnectionSnapshot {
        bindings = List.copyOf(bindings);
    }


    /** 도구 목록을 받지 않은 연결의 상태다. */
    public ConnectionSnapshot(
        String connectorId,
        ConnectionStatus status,
        Map<String, String> secretPrefixes,
        Map<String, String> values,
        Instant checkedAt,
        List<BoundAgentSummary> bindings,
        int undeclaredTools) {
        this(connectorId, status, secretPrefixes, values, checkedAt, bindings, undeclaredTools, null, null);
    }


    public static ConnectionSnapshot from(ConnectorConnection connection, List<ConnectorBinding> bindings) {
        ListedTools listed = connection.listedTools();
        return new ConnectionSnapshot(
            connection.connectorId(),
            connection.status(),
            connection.fields().secretPrefixes(),
            connection.fields().values(),
            connection.checkedAt(),
            bindings.stream().map(BoundAgentSummary::from).toList(),
            connection.undeclaredTools(),
            listed == null ? null : listed.tools().size(),
            listed == null ? null : listed.readOnlyCount());
    }
}
