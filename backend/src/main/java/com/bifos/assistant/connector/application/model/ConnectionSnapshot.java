package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import java.time.Instant;
import java.util.Map;

/**
 * 자기 연결 하나의 상태다. 비밀 칸은 앞부분만 담는다.
 *
 * @param secretPrefixes 비밀 칸의 앞 4자. 값이 16자 미만인 칸은 없다
 * @param values 비밀이 아닌 칸의 값
 */
public record ConnectionSnapshot(
        String connectorId,
        ConnectionStatus status,
        Map<String, String> secretPrefixes,
        Map<String, String> values,
        boolean restartRequired,
        Instant checkedAt,
        String agentCode) {

    public static ConnectionSnapshot from(ConnectorConnection connection) {
        return new ConnectionSnapshot(
                connection.connectorId(),
                connection.status(),
                connection.fields().secretPrefixes(),
                connection.fields().values(),
                connection.restartRequired(),
                connection.checkedAt(),
                connection.agent().code());
    }
}
