package com.bifos.assistant.chat.delivery.domain;

import com.bifos.assistant.shared.domain.CreatedAtEntity;
import com.bifos.assistant.chat.delivery.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.chat.delivery.domain.type.DeliveryStatus;
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
 * 자동 turn 하나가 부모 대화에 넘긴 결과들의 묶음과 그 마지막 전달 시도다(ADR-075, ADR-20261102 / table-reduce-r2).
 *
 * <p>알림 줄을 저장하는 트랜잭션에서 항목, 첫 시도와 함께 생긴다. 항목은 {@code items_json} 에, 시도는 마지막 시도 하나만
 * {@code last_attempt_*} 칸에 둔다. 다시 전달하면 마지막 시도 칸을 새 시도로 덮는다. 묶음의 상태는 마지막 시도가 끝난
 * 방식과 같고, 두 상태를 한 update 에서 바꾼다.
 *
 * <p>마지막 시도의 번호는 {@code attempt_count} 다. 직전 이미지가 쓴 줄은 새 칸이 비어 있을 수 있다.
 */
@Entity
@Table(name = "result_delivery")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResultDelivery extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryStatus status;

    /** 지금까지 만든 시도 수다. 마지막 시도의 번호와 같다. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** 상태가 바뀐 시각이다. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 묶음에 든 결과들의 JSON 문자열이다. 모양은 {@code ResultDeliveryRecorder} 가 정한다. */
    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_attempt_status", length = 20)
    private DeliveryAttemptStatus lastAttemptStatus;

    /** 마지막 시도의 부모 turn 실행 줄이다. 실행 줄이 생기기 전에 끝났으면 비어 있다. */
    @Column(name = "last_attempt_execution_id")
    private Long lastAttemptExecutionId;

    /** 마지막 시도가 저장한 마지막 {@code SYSTEM} 줄이다. */
    @Column(name = "last_attempt_notice_message_id")
    private Long lastAttemptNoticeMessageId;

    /** 마지막 시도가 {@code FAILED} 일 때의 원인이다. */
    @Column(name = "last_attempt_error_code", length = 64)
    private String lastAttemptErrorCode;

    @Column(name = "last_attempt_started_at")
    private Instant lastAttemptStartedAt;

    /** 마지막 시도가 {@code RUNNING} 이면 비어 있다. */
    @Column(name = "last_attempt_finished_at")
    private Instant lastAttemptFinishedAt;


    private ResultDelivery(Long conversationId, String itemsJson, Long noticeMessageId, Instant now) {
        this.conversationId = conversationId;
        this.status = DeliveryStatus.DELIVERING;
        this.attemptCount = 1;
        this.updatedAt = now;
        this.itemsJson = itemsJson;
        this.lastAttemptStatus = DeliveryAttemptStatus.RUNNING;
        this.lastAttemptNoticeMessageId = noticeMessageId;
        this.lastAttemptStartedAt = now;
    }


    /**
     * 첫 시도가 도는 중인 묶음이다. 실행 줄은 만든 뒤에 잇는다.
     *
     * @param itemsJson 묶음에 든 결과들의 JSON 문자열
     * @param noticeMessageId 첫 시도가 저장한 마지막 알림 줄
     */
    public static ResultDelivery opened(Long conversationId, String itemsJson, Long noticeMessageId, Instant now) {
        return new ResultDelivery(conversationId, itemsJson, noticeMessageId, now);
    }
}
