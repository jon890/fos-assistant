package com.bifos.assistant.connector.application.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** HTTPに渡す現在の公開値だけを短い読み取りトランザクションからコピーする。 */
public record ConnectorExecutionReadContext(
        UUID actionPublicId,
        Long actionId,
        Long userId,
        Long agentId,
        Long originExecutionId,
        Long connectionId,
        Long bindingId,
        String profile,
        String connectorId,
        String tool,
        String hermesTool,
        String mcpServer,
        String protocol,
        Instant connectionUpdatedAt,
        Instant bindingUpdatedAt,
        Map<String, String> publicFields) {
    public ConnectorExecutionReadContext {
        publicFields = Map.copyOf(publicFields);
    }
}
