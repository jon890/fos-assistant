package com.bifos.assistant.attention.domain;

import com.bifos.assistant.attention.domain.type.AttentionAction;
import com.bifos.assistant.attention.domain.type.CardKey;
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
 * 사용자가 한 카드의 한 항목에 건 숨기기나 미루기 한 줄이다.
 *
 * <p>칸의 뜻은 {@code docs/backend/schema/attention.md} 의 「attention_control」 이 갖는다. 한 사용자의 한 카드의 한 항목에 한 줄이고,
 * 새 제어는 그 줄을 고친다. 같은 항목이 두 카드에 나와도 카드마다 줄이 따로라 서로 덮어쓰지 않는다. 판정에 넘기는 값
 * {@code AttentionControl} 과 이름이 겹치지 않게 {@code Entry} 를 붙였다.
 */
@Entity
@Table(
        name = "attention_control",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_attention_control_item",
                        columnNames = {"user_id", "card_key", "item_key"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttentionControlEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 제어를 건 카드. {@code FAILURES} 같은 이름으로 저장한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "card_key", nullable = false, length = 16)
    private CardKey cardKey;

    @Column(name = "item_key", nullable = false, length = 80)
    private String itemKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private AttentionAction action;

    /** {@code HIDE} 일 때 숨긴 상태의 지문이다. {@code SNOOZE} 면 비어 있다. */
    @Column(name = "state_key", length = 64)
    private String stateKey;

    /** {@code SNOOZE} 의 기한이다. {@code HIDE} 면 비어 있다. */
    @Column(name = "until_at")
    private Instant untilAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 사용자가 본 상태 {@code stateKey} 가 바뀔 때까지 숨기는 줄이다. */
    public static AttentionControlEntry hide(
            Long userId, CardKey cardKey, String itemKey, String stateKey, Instant now) {
        AttentionControlEntry entry = create(userId, cardKey, itemKey, now);
        entry.rehide(stateKey, now);
        return entry;
    }

    /** {@code until} 까지 미루는 줄이다. */
    public static AttentionControlEntry snooze(
            Long userId, CardKey cardKey, String itemKey, Instant until, Instant now) {
        AttentionControlEntry entry = create(userId, cardKey, itemKey, now);
        entry.resnooze(until, now);
        return entry;
    }

    /** 같은 줄을 숨기기로 고친다. 미룬 기한은 비운다. */
    public void rehide(String stateKey, Instant now) {
        this.action = AttentionAction.HIDE;
        this.stateKey = Objects.requireNonNull(stateKey, "stateKey");
        this.untilAt = null;
        this.updatedAt = micros(now);
    }

    /** 같은 줄을 미루기로 고친다. 숨긴 상태는 비운다. */
    public void resnooze(Instant until, Instant now) {
        this.action = AttentionAction.SNOOZE;
        this.untilAt = micros(Objects.requireNonNull(until, "until"));
        this.stateKey = null;
        this.updatedAt = micros(now);
    }

    private static AttentionControlEntry create(Long userId, CardKey cardKey, String itemKey, Instant now) {
        AttentionControlEntry entry = new AttentionControlEntry();
        entry.userId = Objects.requireNonNull(userId, "userId");
        entry.cardKey = Objects.requireNonNull(cardKey, "cardKey");
        entry.itemKey = Objects.requireNonNull(itemKey, "itemKey");
        entry.createdAt = micros(now);
        return entry;
    }

    /** 칸이 {@code DATETIME(6)} 이라 마이크로초까지만 둔다. 메모리의 값과 DB 에서 다시 읽은 값이 같아야 한다. */
    private static Instant micros(Instant instant) {
        return Objects.requireNonNull(instant, "now").truncatedTo(ChronoUnit.MICROS);
    }
}
