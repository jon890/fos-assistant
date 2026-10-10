package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ToolPolicy;
import java.util.Map;
import java.util.Set;

/** 응답의 가변 JSON이나 manifest를 남기지 않는 검증된 선언이다. */
public record ConnectorExecutionCatalog(
        String connectorId,
        String mcpServer,
        ConnectorExecutionGuardContract guard,
        Map<String, ToolPolicy> toolPolicies,
        Set<String> publicFieldNames) {
    public ConnectorExecutionCatalog {
        toolPolicies = Map.copyOf(toolPolicies);
        publicFieldNames = Set.copyOf(publicFieldNames);
    }
}
