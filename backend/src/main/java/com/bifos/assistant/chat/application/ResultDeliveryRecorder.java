package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.DeliveryItemRef;
import com.bifos.assistant.chat.application.model.ResultDeliveryStart;
import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.ResultDeliveryAttempt;
import com.bifos.assistant.chat.domain.ResultDeliveryItem;
import com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.chat.infra.ResultDeliveryAttemptRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryItemRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결과 전달의 묶음과 시도를 적는 자리다(ADR-070). 전달 기록을 쓰는 곳은 이 클래스 하나다.
 *
 * <p>묶음과 시도의 상태는 늘 같은 트랜잭션에서 바꾼다.
 */
@Service
@RequiredArgsConstructor
public class ResultDeliveryRecorder {

    /** 위임 결과의 출처 이름이다. 결과 이름은 위임 실행 줄의 번호다. */
    public static final String DELEGATION_SOURCE = "DELEGATION";

    private final ResultDeliveryRepository deliveries;
    private final ResultDeliveryItemRepository deliveryItems;
    private final ResultDeliveryAttemptRepository attempts;
    private final Clock clock;

    /**
     * 묶음, 항목, 첫 시도를 저장한다. 알림 줄을 저장하는 트랜잭션 안에서 부른다.
     *
     * <p>같은 결과가 이미 다른 묶음에 들어 있으면 항목의 유일 제약에 걸려 부르는 쪽 트랜잭션이 함께 되돌아간다.
     *
     * @param items 묶음에 넣을 결과들. 넣은 순서가 다시 전달할 때 입력의 순서다
     * @param noticeMessageId 이 시도가 저장한 마지막 알림 줄
     * @throws IllegalArgumentException {@code items} 가 비었을 때
     */
    @Transactional
    public ResultDeliveryStart open(
            Long conversationId, List<DeliveryItemRef> items, Long noticeMessageId, Instant now) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a result delivery needs at least one item");
        }
        ResultDelivery delivery = deliveries.save(ResultDelivery.opened(conversationId, now));
        for (DeliveryItemRef item : items) {
            deliveryItems.save(ResultDeliveryItem.of(delivery.id(), item.source(), item.resultKey()));
        }
        ResultDeliveryAttempt attempt =
                attempts.save(ResultDeliveryAttempt.started(delivery.id(), 1, noticeMessageId, now));
        return new ResultDeliveryStart(delivery.id(), attempt.id());
    }

    /** 시도에 부모 turn 의 실행 줄을 잇는다. 이미 이어진 시도는 그대로 둔다. */
    @Transactional
    public void attachExecution(Long attemptId, Long executionId) {
        attempts.attachExecution(attemptId, executionId);
    }

    /**
     * 도는 중인 시도를 닫고 그 묶음의 상태를 시도의 끝에 맞춘다. 이미 닫힌 시도면 아무것도 하지 않는다.
     *
     * @throws IllegalArgumentException {@code status} 가 {@code RUNNING} 일 때
     */
    @Transactional
    public void finish(Long attemptId, DeliveryAttemptStatus status, String errorCode) {
        if (status == DeliveryAttemptStatus.RUNNING) {
            throw new IllegalArgumentException("an attempt cannot finish as RUNNING");
        }
        Long deliveryId = attempts.findById(attemptId)
                .map(ResultDeliveryAttempt::deliveryId)
                .orElse(null);
        if (deliveryId == null) {
            return;
        }
        Instant now = clock.instant();
        if (attempts.finish(attemptId, status, errorCode, now) == 0) {
            return;
        }
        deliveries.changeStatus(deliveryId, status.deliveryStatus(), now);
    }
}
