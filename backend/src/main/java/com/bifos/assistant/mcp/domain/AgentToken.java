package com.bifos.assistant.mcp.domain;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
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
 * <p>토큰은 어느 profile 이 부르는지만 증명하고 사용자를 정하지 않는다(ADR-032). {@code user_id} 는 profile 이
 * 묶이기 전에 사용자 기준으로 발급한 옛 토큰에만 남아 있고, 옮겨 가는 동안에만 읽는다.
 */
@Entity
@Table(name = "agent_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id") private Long userId;
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

    /**
     * profile 이 빈 옛 토큰을 그 profile 에 묶는다. 묶은 순간부터 그 토큰에는 옛 경로가 없다.
     *
     * <p>한 번 묶은 profile 은 바꾸지 않는다. 다른 profile 에 쓰려면 새로 발급한다.
     *
     * <p>{@code user_id} 도 함께 비운다. 옛 판의 서버로 되돌려도 묶인 토큰이 옛 사용자로 돌지 않고 인증에서 거절된다.
     */
    public void bindProfile(String profileName) {
        if (revokedAt != null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "revoked token cannot be bound");
        if (this.profileName != null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "token is already bound to a profile");
        this.profileName = profileName;
        this.userId = null;
    }

    public void markUsed() { lastUsedAt = Instant.now(); }
    public void revoke() { if (revokedAt == null) revokedAt = Instant.now(); }
    public Long id() { return id; }
    /** 옛 토큰을 발급한 대상 사용자다. 권한 판정에 쓰지 않고, profile 이 빈 토큰의 전환 경로만 읽는다. */
    public Long legacyUserId() { return userId; }
    public String profileName() { return profileName; }
    public String tokenHash() { return tokenHash; }
    public String label() { return label; }
    public Instant createdAt() { return createdAt; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public Instant revokedAt() { return revokedAt; }
}
