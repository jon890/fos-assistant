package com.bifos.assistant.proactive.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 사용자가 에이전트마다 매일 루프를 켠 설정이다(ADR-20261008 / daily-loop). 사용자와 에이전트마다 한 줄이고 줄이 없으면 꺼짐이다.
 *
 * <p>복합 키 엔티티를 만들지 않으려고 대리 키를 두고 {@code (user_id, agent_id)} 를 유일 제약으로 둔다.
 */
@Entity
@Table(
        name = "proactive_loop_setting",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_proactive_loop_setting_user_agent",
                        columnNames = {"user_id", "agent_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class ProactiveLoopSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private Long agentId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** 이 시각 전의 깨우기는 잇지 않는다. 비어 있으면 쉬지 않는다. */
    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ProactiveLoopSetting of(
            Long userId, Long agentId, boolean enabled, Instant snoozedUntil, Instant now) {
        ProactiveLoopSetting row = new ProactiveLoopSetting();
        row.userId = userId;
        row.agentId = agentId;
        row.enabled = enabled;
        row.snoozedUntil = snoozedUntil;
        row.updatedAt = now;
        return row;
    }

    public void change(boolean enabled, Instant snoozedUntil, Instant now) {
        this.enabled = enabled;
        this.snoozedUntil = snoozedUntil;
        this.updatedAt = now;
    }

    /** 지금 쉬는 중이다. 쉬기 시각이 지났으면 비운 것과 같다. */
    public boolean snoozedAt(Instant now) {
        return snoozedUntil != null && snoozedUntil.isAfter(now);
    }
}
