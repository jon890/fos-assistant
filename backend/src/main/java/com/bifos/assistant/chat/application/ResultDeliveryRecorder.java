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
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과 전달의 묶음과 시도를 적는 자리다(ADR-070). 전달 기록을 쓰는 곳은 이 클래스 하나다.
 *
 * <p>묶음과 시도의 상태는 늘 같은 트랜잭션에서 바꾼다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultDeliveryRecorder {

    /** 위임 결과의 출처 이름이다. 결과 이름은 위임 실행 줄의 번호다. */
    public static final String DELEGATION_SOURCE = "DELEGATION";

    /** 부모 turn 의 실행 줄 없이 프로세스가 내려가 끝을 모르는 시도에 적는 오류 코드다. */
    public static final String INTERRUPTED = "INTERRUPTED";

    private final ResultDeliveryRepository deliveries;
    private final ResultDeliveryItemRepository deliveryItems;
    private final ResultDeliveryAttemptRepository attempts;
    private final AgentExecutionRepository executions;
    private final TransactionTemplate transactions;
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
        close(attemptId, status, errorCode);
    }

    /**
     * 그 실행 줄을 이은 도는 중인 시도를 실행 줄이 끝난 방식으로 닫는다. 실행 줄을 적는 트랜잭션 안에서 부른다.
     *
     * <p>{@code SUCCEEDED} 는 {@code SUCCEEDED}, {@code FAILED} 는 실행 줄의 오류 코드와 함께 {@code FAILED},
     * {@code CANCELLED} 는 {@code STOPPED} 다. 실행 줄이 아직 {@code RUNNING} 이거나 이은 시도가 없으면 아무것도 하지
     * 않는다.
     */
    @Transactional
    public void finishByExecution(AgentExecution execution) {
        DeliveryAttemptStatus status = attemptStatusOf(execution);
        if (status == null) {
            return;
        }
        attempts.findFirstByExecutionIdAndStatus(execution.id(), DeliveryAttemptStatus.RUNNING)
                .ifPresent(attempt -> close(attempt.id(), status, errorCodeOf(execution, status)));
    }

    /**
     * 그 시각 전에 시작해 {@code RUNNING} 으로 남은 시도를 닫고 닫은 수를 돌려준다. 이전 프로세스가 남긴 시도를 기동할 때
     * 닫는다.
     *
     * <p>시도마다 트랜잭션을 따로 연다. 실행 줄이 없거나 지워졌으면 {@code FAILED} 와 {@link #INTERRUPTED} 로, 실행 줄이
     * 끝났으면 {@link #finishByExecution} 과 같은 규칙으로 닫는다. 실행 줄이 아직 {@code RUNNING} 이면 건너뛴다. 기동
     * 정리가 그 줄을 정할 때 함께 닫는다. 한 시도를 닫다 실패하면 경고 로그만 남기고 다음 시도로 간다.
     */
    public int closeLeftovers(Instant startedBefore) {
        int closed = 0;
        for (ResultDeliveryAttempt attempt :
                attempts.findByStatusAndStartedAtBefore(DeliveryAttemptStatus.RUNNING, startedBefore)) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> closeLeftover(attempt)))) {
                    closed++;
                }
            } catch (RuntimeException ex) {
                log.warn("기동 전에 남은 전달 시도를 닫지 못했다 attemptId={}", attempt.id(), ex);
            }
        }
        return closed;
    }

    private boolean closeLeftover(ResultDeliveryAttempt attempt) {
        AgentExecution execution = attempt.executionId() == null
                ? null
                : executions.findById(attempt.executionId()).orElse(null);
        if (execution == null) {
            return close(attempt.id(), DeliveryAttemptStatus.FAILED, INTERRUPTED);
        }
        DeliveryAttemptStatus status = attemptStatusOf(execution);
        if (status == null) {
            return false;
        }
        return close(attempt.id(), status, errorCodeOf(execution, status));
    }

    /** 도는 중인 시도를 닫고 묶음 상태를 맞춘다. 실제로 닫았으면 참이다. 트랜잭션 안에서 부른다. */
    private boolean close(Long attemptId, DeliveryAttemptStatus status, String errorCode) {
        if (status == DeliveryAttemptStatus.RUNNING) {
            throw new IllegalArgumentException("an attempt cannot finish as RUNNING");
        }
        Long deliveryId = attempts.findById(attemptId)
                .map(ResultDeliveryAttempt::deliveryId)
                .orElse(null);
        if (deliveryId == null) {
            return false;
        }
        Instant now = clock.instant();
        if (attempts.finish(attemptId, status, errorCode, now) == 0) {
            return false;
        }
        deliveries.changeStatus(deliveryId, status.deliveryStatus(), now);
        return true;
    }

    /** 실행 줄이 끝난 방식에 맞는 시도의 상태다. 아직 도는 줄이면 null 이다. */
    private static DeliveryAttemptStatus attemptStatusOf(AgentExecution execution) {
        return switch (execution.status()) {
            case RUNNING -> null;
            case SUCCEEDED -> DeliveryAttemptStatus.SUCCEEDED;
            case FAILED -> DeliveryAttemptStatus.FAILED;
            case CANCELLED -> DeliveryAttemptStatus.STOPPED;
        };
    }

    private static String errorCodeOf(AgentExecution execution, DeliveryAttemptStatus status) {
        return status == DeliveryAttemptStatus.FAILED ? execution.errorCode() : null;
    }
}
