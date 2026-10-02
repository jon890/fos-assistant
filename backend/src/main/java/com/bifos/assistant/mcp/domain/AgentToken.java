package com.bifos.assistant.mcp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Hermes profile 이 Control Plane MCP 를 부를 때 쓰는 장기 토큰이다.
 *
 * <p>토큰은 profile 만 증명하고 사용자를 갖지 않는다(ADR-032).
 */
@Entity
@Table(name = "agent_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class AgentToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(name = "profile_name", length = 64)
    @Getter
    private String profileName;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    @Getter
    private String tokenHash;

    @Column(nullable = false, length = 100)
    @Getter
    private String label;

    @Column(name = "created_at", nullable = false)
    @Getter
    private Instant createdAt;

    @Column(name = "last_used_at")
    @Getter
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    @Getter
    private Instant revokedAt;

    private AgentToken(String profileName, String tokenHash, String label, Instant now) {
        this.profileName = profileName;
        this.tokenHash = tokenHash;
        this.label = label;
        this.createdAt = now;
    }

    /** profile 에 묶인 새 토큰을 만든다. 사용자로 발급하는 길은 없다. */
    public static AgentToken issueFor(String profileName, String tokenHash, String label, Instant now) {
        return new AgentToken(profileName, tokenHash, label, now);
    }

    public void markUsed(Instant now) {
        lastUsedAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
