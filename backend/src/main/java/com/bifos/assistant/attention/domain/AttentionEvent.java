package com.bifos.assistant.attention.domain;

import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
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
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 먼저 알리기의 지표 사건 한 줄이다.
 *
 * <p>칸의 뜻은 {@code docs/backend/schema/attention.md} 의 「attention_event」 가 갖는다. 같은 사용자, 항목, 상태, 사건 종류, 판정에 한
 * 줄만 남긴다. 같은 상태에서 {@code LATER} 가 {@code NOW} 로 바뀌면 {@code NOW} 의 줄이 따로 남는다. 제목과 본문을 담지 않는다.
 */
@Entity
@Table(
        name = "attention_event",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_attention_event_once",
                        columnNames = {"user_id", "item_key", "state_key", "event_type", "attention"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttentionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_key", nullable = false, length = 80)
    private String itemKey;

    @Column(name = "state_key", nullable = false, length = 64)
    private String stateKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 32)
    private AttentionTrigger trigger;

    /** 그때의 판정이다. 억제된 항목에 한 일이면 {@code SUPPRESSED} 다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "attention", nullable = false, length = 16)
    private AttentionLevel level;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private AttentionEventType eventType;

    /** 그때 출처 하나라도 신선도가 {@code STALE} 이었다. */
    @Column(name = "stale", nullable = false)
    private boolean stale;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static AttentionEvent of(
            Long userId,
            String itemKey,
            String stateKey,
            AttentionTrigger trigger,
            AttentionLevel level,
            AttentionEventType type,
            boolean stale,
            Instant now) {
        AttentionEvent event = new AttentionEvent();
        event.userId = Objects.requireNonNull(userId, "userId");
        event.itemKey = Objects.requireNonNull(itemKey, "itemKey");
        event.stateKey = Objects.requireNonNull(stateKey, "stateKey");
        event.trigger = Objects.requireNonNull(trigger, "trigger");
        event.level = Objects.requireNonNull(level, "level");
        event.eventType = Objects.requireNonNull(type, "type");
        event.stale = stale;
        // 칸이 DATETIME(6) 이라 마이크로초까지만 둔다. 메모리의 값과 DB 에서 다시 읽은 값이 같아야 한다.
        event.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
        return event;
    }
}
