package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ConnectorToolGrant;
import java.time.Instant;

/**
 * 주인에게 보이는 상시 허락 한 줄이다.
 *
 * @param title 사람에게 보일 이름. 카탈로그의 선언에 없으면 원래 도구 이름
 */
public record ConnectorGrantView(Long grantId, String connectorId, String toolName, String title, Instant expiresAt) {

    /** @param declaredTitle 카탈로그가 선언한 이름. 없거나 읽지 못했으면 null */
    public static ConnectorGrantView from(ConnectorToolGrant grant, String declaredTitle) {
        return new ConnectorGrantView(
                grant.id(),
                grant.connectorId(),
                grant.toolName(),
                declaredTitle == null ? grant.toolName() : declaredTitle,
                grant.expiresAt());
    }
}
