package com.bifos.assistant.memory.domain;

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
 * 다른 서비스가 사용자의 문서를 읽을 때 쓰는 토큰이다(ADR-056).
 *
 * <p>원문은 저장하지 않고 해시만 둔다. 사용자 한 사람에 묶이고 만료가 늘 있다. 폐기해도 줄을 지우지 않는다.
 */
@Entity
@Table(name = "service_token")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    private ServiceToken(Long userId, String tokenHash, String label, Instant expiresAt, Instant now) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.label = label;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public static ServiceToken issue(Long userId, String tokenHash, String label, Instant expiresAt, Instant now) {
        return new ServiceToken(userId, tokenHash, label, expiresAt, now);
    }

    public void markUsed(Instant at) {
        this.lastUsedAt = at;
    }

    /** 폐기한다. 이미 폐기됐으면 처음 시각을 그대로 둔다. */
    public void revoke(Instant at) {
        if (revokedAt == null) {
            this.revokedAt = at;
        }
    }

    /** 폐기되지 않았고 만료 시각이 아직 오지 않았는가. */
    public boolean usableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
