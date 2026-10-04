package com.bifos.assistant.chat.domain;

import com.bifos.assistant.chat.domain.type.DeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 자동 turn 하나가 부모 대화에 넘긴 결과들의 묶음이다(ADR-075).
 *
 * <p>알림 줄을 저장하는 트랜잭션에서 항목, 첫 시도와 함께 생긴다. 상태는 마지막 시도가 끝난 방식과 같고, 시도의 상태와
 * 같은 트랜잭션에서 바꾼다.
 */
@Entity
@Table(name = "result_delivery")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResultDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryStatus status;

    /** 지금까지 만든 시도 수다. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 상태가 바뀐 시각이다. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private ResultDelivery(Long conversationId, DeliveryStatus status, int attemptCount, Instant now) {
        this.conversationId = conversationId;
        this.status = status;
        this.attemptCount = attemptCount;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 첫 시도와 함께 여는 묶음이다. */
    public static ResultDelivery opened(Long conversationId, Instant now) {
        return new ResultDelivery(conversationId, DeliveryStatus.DELIVERING, 1, now);
    }
}
