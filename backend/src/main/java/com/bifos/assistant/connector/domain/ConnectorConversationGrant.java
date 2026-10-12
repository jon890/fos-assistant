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
 * 사용자가 한 대화에서 커넥터 도구 하나를 계속 허용한 기록이다(ADR-20261012 / conversation-grant).
 *
 * <p>칸의 뜻은 {@code backend/docs/data-schema.md} 의 「connector_conversation_grant」 가 갖는다. 그 대화의 그 에이전트 실행에서만
 * 통한다. 거두지 않았고 기간이 남았고 대화가 지워지지 않은 줄만 유효하다.
 */
@Entity
@Table(
        name = "connector_conversation_grant",
        indexes = {
            @Index(
                    name = "idx_connector_conversation_grant_lookup",
                    columnList = "conversation_id, agent_id, connector_id, tool_name"),
            @Index(name = "idx_connector_conversation_grant_connector", columnList = "user_id, connector_id")
        })
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorConversationGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 허락을 준 승인 줄을 판정한 실행의 에이전트다. 같은 대화라도 다른 에이전트의 실행에는 통하지 않는다. */
    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "connector_id", nullable = false, length = 64)
    private String connectorId;

    /** MCP 서버의 원래 도구 이름이다. */
    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    /** 이 허락을 함께 준 승인 줄의 번호다. 그 결과의 알림 줄이 허락을 함께 알린다. */
    @Column(name = "action_id", nullable = false)
    private Long actionId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 사용자가 거두었거나, 연결을 해제하거나 다시 등록했거나, 에이전트에서 연결을 뗀 시각이다. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public static ConnectorConversationGrant of(ConnectorAction action, Instant expiresAt, Instant now) {
        ConnectorConversationGrant grant = new ConnectorConversationGrant();
        grant.userId = action.userId();
        grant.conversationId = action.conversationId();
        grant.agentId = action.agentId();
        grant.connectorId = action.connectorId();
        grant.toolName = action.toolName();
        grant.actionId = action.id();
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
