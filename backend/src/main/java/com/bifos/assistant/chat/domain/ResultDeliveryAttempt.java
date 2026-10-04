package com.bifos.assistant.chat.domain;

import com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 전달 묶음을 부모 대화에 넘긴 한 번이다(ADR-070).
 *
 * <p>부모 turn 의 실행 줄을 만들면 그 번호를 잇고, 그 turn 이 끝난 방식으로 닫는다. 실행 줄이 생기기 전에 끝났으면
 * {@code execution_id} 는 비어 있다.
 */
@Entity
@Table(
        name = "result_delivery_attempt",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_result_delivery_attempt_no",
                    columnNames = {"delivery_id", "attempt_no"})
        })
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResultDeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    /** 1 부터 센다. */
    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryAttemptStatus status;

    /** 이 시도의 부모 turn 실행 줄이다. */
    @Column(name = "execution_id")
    private Long executionId;

    /** 이 시도가 저장한 마지막 {@code SYSTEM} 줄이다. */
    @Column(name = "notice_message_id")
    private Long noticeMessageId;

    /** {@code FAILED} 의 원인이다. */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** {@code RUNNING} 이면 비어 있다. */
    @Column(name = "finished_at")
    private Instant finishedAt;

    private ResultDeliveryAttempt(Long deliveryId, int attemptNo, Long noticeMessageId, Instant now) {
        this.deliveryId = deliveryId;
        this.attemptNo = attemptNo;
        this.status = DeliveryAttemptStatus.RUNNING;
        this.noticeMessageId = noticeMessageId;
        this.startedAt = now;
    }

    /** 도는 중인 시도다. 실행 줄은 만든 뒤에 잇는다. */
    public static ResultDeliveryAttempt started(Long deliveryId, int attemptNo, Long noticeMessageId, Instant now) {
        return new ResultDeliveryAttempt(deliveryId, attemptNo, noticeMessageId, now);
    }
}
