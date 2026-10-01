package com.bifos.assistant.connector.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 사용자가 커넥터 도구 하나에 준 상시 허락이다(ADR-048).
 *
 * <p>칸의 뜻은 {@code docs/data-schema.md} 의 「connector_tool_grant」 가 갖는다. 거두지 않았고 기간이 남은 줄만
 * 유효하다. 같은 도구에 다시 주면 새 줄을 만든다.
 */
@Entity
@Table(
        name = "connector_tool_grant",
        indexes = @Index(name = "idx_connector_tool_grant_lookup", columnList = "user_id, connector_id, tool_name"))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorToolGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "connector_id", nullable = false, length = 64)
    private String connectorId;

    /** MCP 서버의 원래 도구 이름이다. */
    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 사용자가 거두었거나 연결을 해제한 시각이다. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public static ConnectorToolGrant of(
            Long userId, String connectorId, String toolName, Instant expiresAt, Instant now) {
        ConnectorToolGrant grant = new ConnectorToolGrant();
        grant.userId = userId;
        grant.connectorId = connectorId;
        grant.toolName = toolName;
        grant.expiresAt = expiresAt;
        grant.createdAt = now;
        return grant;
    }

    /** 이미 거둔 허락은 처음 거둔 시각을 그대로 둔다. */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }
}
