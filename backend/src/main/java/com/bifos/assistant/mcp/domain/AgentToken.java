package com.bifos.assistant.mcp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "agent_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(nullable = false, length = 100) private String label;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    private AgentToken(Long userId, String tokenHash, String label) {
        this.userId = userId; this.tokenHash = tokenHash; this.label = label; this.createdAt = Instant.now();
    }
    public static AgentToken issue(Long userId, String tokenHash, String label) { return new AgentToken(userId, tokenHash, label); }
    public void markUsed() { lastUsedAt = Instant.now(); }
    public void revoke() { if (revokedAt == null) revokedAt = Instant.now(); }
    public Long id() { return id; }
    public Long userId() { return userId; }
    public String tokenHash() { return tokenHash; }
    public String label() { return label; }
    public Instant createdAt() { return createdAt; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public Instant revokedAt() { return revokedAt; }
}
