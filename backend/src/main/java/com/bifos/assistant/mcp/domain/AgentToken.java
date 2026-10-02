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

/**
 * Hermes profile 이 Control Plane MCP 를 부를 때 쓰는 장기 토큰이다.
 *
 * <p>토큰은 profile 만 증명하고 사용자를 갖지 않는다(ADR-032).
 */
@Entity
@Table(name = "agent_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "profile_name", length = 64) private String profileName;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(nullable = false, length = 100) private String label;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    private AgentToken(String profileName, String tokenHash, String label) {
        this.profileName = profileName; this.tokenHash = tokenHash; this.label = label; this.createdAt = Instant.now();
    }

    /** profile 에 묶인 새 토큰을 만든다. 사용자로 발급하는 길은 없다. */
    public static AgentToken issueFor(String profileName, String tokenHash, String label) {
        return new AgentToken(profileName, tokenHash, label);
    }

    public void markUsed() { lastUsedAt = Instant.now(); }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }

    public Long id() { return id; }

    public String profileName() { return profileName; }

    public String tokenHash() { return tokenHash; }

    public String label() { return label; }

    public Instant createdAt() { return createdAt; }

    public Instant lastUsedAt() { return lastUsedAt; }

    public Instant revokedAt() { return revokedAt; }
}
