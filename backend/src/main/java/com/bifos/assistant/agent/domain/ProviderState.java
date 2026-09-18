package com.bifos.assistant.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * provider 하나가 언제까지 막힌 것으로 볼지 적어 둔다.
 *
 * <p>에이전트마다 두지 않는다. credential 은 provider 마다 하나의 묶음이고 막힌 것은 그 묶음이다.
 * 에이전트마다 두면 같은 provider 가 막힌 것을 에이전트 수만큼 따로 배운다.
 *
 * <p>Hermes 는 남은 시간을 HTTP 로 알려주지 않는다. {@code hermes auth list} 만 알고 그것은 컨테이너
 * 안의 명령이다. 그래서 우리가 정한 시간으로 식힌다.
 */
@Entity
@Table(name = "provider_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderState {

    @Id
    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "blocked_until", nullable = false)
    private Instant blockedUntil;

    @Column(name = "blocked_reason", length = 255)
    private String blockedReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private ProviderState(String provider, Instant blockedUntil, String blockedReason) {
        this.provider = provider;
        this.blockedUntil = blockedUntil;
        this.blockedReason = blockedReason;
        this.updatedAt = Instant.now();
    }

    public static ProviderState blocked(String provider, Instant until, String reason) {
        return new ProviderState(provider, until, reason);
    }

    public String provider() {
        return provider;
    }

    public Instant blockedUntil() {
        return blockedUntil;
    }

    public String blockedReason() {
        return blockedReason;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** 이 시각에 아직 막혀 있는가. */
    public boolean blockedAt(Instant now) {
        return blockedUntil.isAfter(now);
    }

    /** 막힘을 다시 걸거나 시간을 늘린다. */
    public void blockUntil(Instant until, String reason) {
        this.blockedUntil = until;
        this.blockedReason = reason;
        this.updatedAt = Instant.now();
    }

    /** 막힘을 푼다. 성공한 실행이 그 provider 가 쓸 수 있다는 것을 보였을 때 부른다. */
    public void release(Instant now) {
        this.blockedUntil = now;
        this.blockedReason = null;
        this.updatedAt = Instant.now();
    }
}
