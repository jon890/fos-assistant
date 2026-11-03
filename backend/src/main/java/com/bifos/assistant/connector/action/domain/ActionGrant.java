package com.bifos.assistant.connector.action.domain;

import com.bifos.assistant.connector.action.domain.type.GrantScope;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 승인 줄이 승인하면서 함께 준 허락이다(ADR-20261102 / table-reduce-r2). 승인 줄의 {@code grant_*} 칸으로 저장하고, 허락을 주지 않은
 * 줄은 칸이 모두 비어 있다(승인 줄의 {@code grant} 가 null).
 *
 * <p>칸의 뜻은 {@code backend/docs/data-schema.md} 의 「상시 허락과 대화 허락」 이 갖는다. 거두지 않았고 기간이 남은 허락만 유효하다.
 */
@Embeddable
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionGrant {

    @Enumerated(EnumType.STRING)
    @Column(name = "grant_scope", length = 16)
    private GrantScope scope;

    /** 허락이 끝나는 시각이다. 무기한은 없다. */
    @Column(name = "grant_expires_at")
    private Instant expiresAt;

    /** 허락을 거둔 시각이다. 처음 거둔 시각을 그대로 둔다. */
    @Column(name = "grant_revoked_at")
    private Instant revokedAt;


    static ActionGrant of(GrantScope scope, Instant expiresAt) {
        ActionGrant grant = new ActionGrant();
        grant.scope = Objects.requireNonNull(scope);
        grant.expiresAt = Objects.requireNonNull(expiresAt);
        return grant;
    }


    /** {@code now} 에 이 허락이 통하는가. */
    public boolean activeAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }


    /** 이미 거둔 허락은 처음 거둔 시각을 그대로 둔다. */
    void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }
}
