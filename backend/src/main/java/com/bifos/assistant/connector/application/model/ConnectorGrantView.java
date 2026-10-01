package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ConnectorToolGrant;
import java.time.Instant;

/** 주인에게 보이는 상시 허락 한 줄이다. */
public record ConnectorGrantView(Long grantId, String connectorId, String toolName, Instant expiresAt) {

    public static ConnectorGrantView from(ConnectorToolGrant grant) {
        return new ConnectorGrantView(grant.id(), grant.connectorId(), grant.toolName(), grant.expiresAt());
    }
}
