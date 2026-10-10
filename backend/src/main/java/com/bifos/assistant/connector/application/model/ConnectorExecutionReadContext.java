package com.bifos.assistant.connector.application.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** HTTP에 넘길 현재 공개 값만 짧은 읽기 트랜잭션에서 복사한다. */
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
