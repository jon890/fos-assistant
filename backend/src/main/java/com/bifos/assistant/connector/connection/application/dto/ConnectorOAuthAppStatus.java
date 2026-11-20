package com.bifos.assistant.connector.connection.application.dto;

import java.time.Instant;

/** 관리자 화면에 보일 앱 설정이다. client secret 은 표시할 앞부분만 담는다. */
public record ConnectorOAuthAppStatus(
    String connectorId,
    String connectorTitle,
    String provider,
    String clientId,
    String appSlug,
    boolean set,
    String prefix,
    Long updatedBy,
    Instant updatedAt) {
}
