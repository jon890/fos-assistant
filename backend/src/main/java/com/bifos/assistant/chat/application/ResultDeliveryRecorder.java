package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.DeliveryItemRef;
import com.bifos.assistant.chat.application.model.DeliveryState;
import com.bifos.assistant.chat.application.model.ResultDeliveryStart;
import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.ResultDeliveryAttempt;
import com.bifos.assistant.chat.domain.ResultDeliveryItem;
import com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.chat.domain.type.DeliveryStatus;
import com.bifos.assistant.chat.infra.ResultDeliveryAttemptRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryItemRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    /** 사용자가 다시 전달할 수 있는 묶음 상태들이다. */
    private static final Set<DeliveryStatus> RETRYABLE = Arrays.stream(DeliveryStatus.values())
            .filter(DeliveryStatus::retryable)
            .collect(Collectors.toUnmodifiableSet());

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

    /**
     * 그 대화의 묶음을 읽는다.
     *
     * @throws ApiException {@code DELIVERY_NOT_FOUND}. 없거나 다른 대화의 묶음일 때
     */
    public ResultDelivery require(Long conversationId, Long deliveryId) {
        return deliveries
                .findByIdAndConversationId(deliveryId, conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.DELIVERY_NOT_FOUND, "no such result delivery"));
    }

    /** 묶음에 넣은 결과들이다. 넣은 순서다. */
    public List<DeliveryItemRef> itemsOf(Long deliveryId) {
        return deliveryItems.findByDeliveryIdOrderByIdAsc(deliveryId).stream()
                .map(item -> new DeliveryItemRef(item.source(), item.resultKey()))
                .toList();
    }

    /**
     * 묶음을 {@code FAILED} 나 {@code STOPPED} 에서 {@code DELIVERING} 으로 바꾸고 새 시도의 번호를 돌려준다. 다시 전달의
     * 알림 줄을 저장하는 트랜잭션 안에서 부른다.
     *
     * <p>조건부 update 라 같은 묶음을 함께 바꾸려는 요청 가운데 하나만 바꾼다. 활성 시도를 하나로 지키는 것은 이 update 다.
     *
     * @return 늘린 뒤의 시도 수. 새 시도의 {@code attempt_no} 다
     * @throws ApiException {@code DELIVERY_NOT_RETRYABLE}. 바뀐 줄이 없을 때
     */
    @Transactional
    public int claimRetry(Long deliveryId, Instant now) {
        if (deliveries.claimRetry(deliveryId, RETRYABLE, now) == 0) {
            throw notRetryable();
        }
        return deliveries
                .findById(deliveryId)
                .map(ResultDelivery::attemptCount)
                .orElseThrow(ResultDeliveryRecorder::notRetryable);
    }

    /** 도는 중인 시도를 저장하고 그 번호를 돌려준다. {@link #claimRetry} 와 같은 트랜잭션에서 부른다. */
    @Transactional
    public Long addAttempt(Long deliveryId, int attemptNo, Long noticeMessageId, Instant now) {
        return attempts.save(ResultDeliveryAttempt.started(deliveryId, attemptNo, noticeMessageId, now))
                .id();
    }

    /**
     * 그 대화의 묶음마다 마지막 시도가 저장한 마지막 알림 줄을 열쇠로, 묶음의 번호와 상태를 낸다. 이력 API 가 알림 줄 아래에
     * 그린다.
     *
     * <p>마지막 시도는 {@code attempt_no} 가 가장 큰 시도다. 그 시도에 알림 줄이 없으면 그 묶음은 빠진다. 질의는 묶음과
     * 시도에 한 번씩이다.
     */
    @Transactional(readOnly = true)
    public Map<Long, DeliveryState> statesByNotice(Long conversationId) {
        Map<Long, ResultDelivery> byId = deliveries.findByConversationId(conversationId).stream()
                .collect(Collectors.toMap(ResultDelivery::id, Function.identity()));
        if (byId.isEmpty()) {
            return Map.of();
        }
        Map<Long, ResultDeliveryAttempt> latest = new HashMap<>();
        for (ResultDeliveryAttempt attempt : attempts.findByDeliveryIdIn(byId.keySet())) {
            latest.merge(
                    attempt.deliveryId(),
                    attempt,
                    (left, right) -> left.attemptNo() >= right.attemptNo() ? left : right);
        }
        Map<Long, DeliveryState> states = new HashMap<>();
        latest.values().stream()
                .filter(attempt -> attempt.noticeMessageId() != null)
                .forEach(attempt -> {
                    ResultDelivery delivery = byId.get(attempt.deliveryId());
                    states.put(attempt.noticeMessageId(), new DeliveryState(delivery.id(), delivery.status()));
                });
        return states;
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

    /** 다시 전달할 수 없는 묶음의 거절이다. 다시 전달을 판정하는 자리가 함께 쓴다. */
    static ApiException notRetryable() {
        return new ApiException(ErrorCode.DELIVERY_NOT_RETRYABLE, "this result delivery cannot be retried now");
    }
}
