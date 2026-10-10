package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ToolPolicy;
import java.util.Map;
import java.util.Set;

/** 応答の可変JSONやmanifestを残さない検証済み宣言だ。 */
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
