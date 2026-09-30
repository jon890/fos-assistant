package com.bifos.assistant.connector.domain;

import com.bifos.assistant.agent.domain.Agent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "accountbook_connection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountbookConnection {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false, unique = true)
    private Agent agent;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionStatus status;
    @Column(name = "family_uuid", length = 36)
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID familyUuid;
    @Column(name = "token_prefix", length = 8)
    private String tokenPrefix;
    @Column(name = "restart_required", nullable = false)
    private boolean restartRequired;
    @Column(name = "desired_enabled", nullable = false)
    private boolean desiredEnabled;
    @Column(name = "checked_at")
    private Instant checkedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private AccountbookConnection(Long userId, Agent agent) {
        this.userId = userId; this.agent = agent; this.status = ConnectionStatus.PENDING;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public static AccountbookConnection pending(Long userId, Agent agent) { return new AccountbookConnection(userId, agent); }
    public void registered(String tokenPrefix, UUID familyUuid, boolean restartRequired) {
        this.tokenPrefix = tokenPrefix; this.familyUuid = familyUuid; this.restartRequired = this.restartRequired || restartRequired;
        this.status = ConnectionStatus.PENDING; this.checkedAt = Instant.now(); this.updatedAt = checkedAt;
        this.desiredEnabled = true;
        agent.changeAccess(false, agent.visibility(), agent.ownerUserId());
    }
    public void ready() { this.status = ConnectionStatus.READY; this.restartRequired = false; this.checkedAt = Instant.now(); this.updatedAt = checkedAt; agent.changeAccess(true, agent.visibility(), agent.ownerUserId()); }
    public void pending() { this.status = ConnectionStatus.PENDING; this.updatedAt = Instant.now(); agent.changeAccess(false, agent.visibility(), agent.ownerUserId()); }
    public void beginRegister() { this.desiredEnabled = false; pending(); }
    public void markRestartRequired(boolean restartRequired) { this.restartRequired = this.restartRequired || restartRequired; }
    public void beginDisconnect() { this.desiredEnabled = false; pending(); }
    public void confirmDisconnected() { this.restartRequired = false; disconnected(false); }
    public void disconnected(boolean restartRequired) { this.status = ConnectionStatus.DISCONNECTED; this.desiredEnabled = false; this.tokenPrefix = null; this.familyUuid = null; this.restartRequired = this.restartRequired || restartRequired; this.checkedAt = Instant.now(); this.updatedAt = checkedAt; agent.changeAccess(false, agent.visibility(), agent.ownerUserId()); }
}
