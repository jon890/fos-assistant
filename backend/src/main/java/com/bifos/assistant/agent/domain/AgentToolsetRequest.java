package com.bifos.assistant.agent.domain;

import com.bifos.assistant.agent.domain.type.ToolsetRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 도구 사용 요청과 결정 이력이다. PENDING만 pendingSlot을 채워 DB에서도 중복을 막는다. */
@Entity
@Table(
        name = "agent_toolset_request",
        uniqueConstraints =
                @UniqueConstraint(
                        columnNames = {"group_id", "agent_id", "requester_user_id", "toolset", "pending_slot"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentToolsetRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "public_id", nullable = false, unique = true, columnDefinition = "binary(16)")
    private UUID publicId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    @Column(nullable = false, length = 64)
    private String toolset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ToolsetRequestStatus status;

    @Column(name = "pending_slot")
    private Integer pendingSlot;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(length = 200)
    private String reason;

    public static AgentToolsetRequest of(Long groupId, Long agentId, Long requester, String toolset, Instant now) {
        AgentToolsetRequest row = new AgentToolsetRequest();
        row.publicId = UUID.randomUUID();
        row.groupId = groupId;
        row.agentId = agentId;
        row.requesterUserId = requester;
        row.toolset = toolset;
        row.status = ToolsetRequestStatus.PENDING;
        row.pendingSlot = 1;
        row.requestedAt = now;
        return row;
    }

    public void finish(ToolsetRequestStatus result, Long decider, String reason, Instant now) {
        if (status != ToolsetRequestStatus.PENDING || result == ToolsetRequestStatus.PENDING) {
            throw new IllegalStateException("only pending requests can finish");
        }
        this.status = result;
        this.pendingSlot = null;
        this.decidedByUserId = decider;
        this.reason = reason;
        this.decidedAt = now;
    }
}
