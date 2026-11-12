package com.bifos.assistant.chat.delivery.application;

import com.bifos.assistant.chat.delivery.application.dto.DeliveryAttemptRef;
import com.bifos.assistant.chat.delivery.application.dto.DeliveryItemRef;
import com.bifos.assistant.chat.delivery.application.dto.DeliveryState;
import com.bifos.assistant.chat.delivery.domain.ResultDelivery;
import com.bifos.assistant.chat.delivery.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.chat.delivery.domain.type.DeliveryStatus;
import com.bifos.assistant.chat.delivery.infra.ResultDeliveryRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결과 전달의 묶음과 시도를 적는 자리다(ADR-075). 전달 기록을 쓰는 곳은 이 클래스 하나다.
 *
 * <p>묶음 줄 하나가 항목({@code items_json})과 마지막 시도({@code last_attempt_*})를 함께 갖는다
 * (ADR-20261102 / table-reduce-r2). 묶음과 시도의 상태는 늘 한 update 에서 바꾼다.
 *
 * <p>{@code items_json} 의 모양은 버전 1 의 {@code {"version":1,"items":[{"source":..,"resultKey":..}]}} 이고 항목은 넣은
 * 순서다. 옛 항목 표에서 옮긴 마이그레이션 {@code V20261102074312__ResultDeliveryItemsAttemptBackfill} 도 같은 모양을
 * 썼다.
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

    /** {@code items_json} 의 버전이다. */
    private static final int ITEMS_VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ResultDeliveryRepository deliveries;
    private final AgentExecutionRepository executions;
    private final TransactionTemplate transactions;
    private final Clock clock;


    /**
     * 항목과 첫 시도를 담은 묶음을 저장한다. 알림 줄을 저장하는 트랜잭션 안에서 부른다.
     *
     * <p>한 결과가 두 묶음에 드는 것은 여기서 막지 않는다. 결과를 낸 쪽의 전달 표시가 같은 트랜잭션에서 그 결과의 줄 하나만
     * 바꾸는지 부르는 쪽이 본다.
     *
     * @param items 묶음에 넣을 결과들. 넣은 순서가 다시 전달할 때 입력의 순서다
     * @param noticeMessageId 이 시도가 저장한 마지막 알림 줄
     * @return 첫 시도
     * @throws IllegalArgumentException {@code items} 가 비었을 때
     */
    @Transactional
    public DeliveryAttemptRef open(
        Long conversationId, List<DeliveryItemRef> items, Long noticeMessageId, Instant now) {
        return open(conversationId, items, noticeMessageId, null, now);
    }


    /**
     * 위임 결과 알림 줄을 함께 적어 묶음을 저장한다. 결과 도착 줄이 맡은 에이전트의 이름표를 이 칸으로 찾는다.
     *
     * @param delegationNoticeMessageId 위임 결과 알림 줄. 묶음에 위임 결과가 없으면 null
     * @throws IllegalArgumentException {@code items} 가 비었을 때
     */
    @Transactional
    public DeliveryAttemptRef open(
        Long conversationId,
        List<DeliveryItemRef> items,
        Long noticeMessageId,
        Long delegationNoticeMessageId,
        Instant now) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a result delivery needs at least one item");
        }
        ResultDelivery delivery = deliveries.save(
            ResultDelivery.opened(conversationId, itemsJson(items), noticeMessageId, delegationNoticeMessageId, now));
        return new DeliveryAttemptRef(delivery.id(), delivery.attemptCount());
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


    /**
     * 묶음에 넣은 결과들이다. 넣은 순서다.
     *
     * <p>{@code items_json} 을 읽지 못하면 빈 목록이다. 다시 전달은 남은 항목이 없어 거절한다.
     */
    public List<DeliveryItemRef> itemsOf(Long deliveryId) {
        return deliveries.findById(deliveryId).map(ResultDelivery::itemsJson).map(json -> readItems(deliveryId, json))
            .orElse(List.of());
    }


    /**
     * 묶음을 {@code FAILED} 나 {@code STOPPED} 에서 {@code DELIVERING} 으로 바꾸고 마지막 시도 칸을 도는 중인 새 시도로
     * 덮는다. 다시 전달의 알림 줄을 저장한 트랜잭션 안에서 부른다. 앞의 시도는 남지 않는다.
     *
     * <p>조건부 update 라 같은 묶음을 함께 바꾸려는 요청 가운데 하나만 바꾼다. 활성 시도를 하나로 지키는 것은 이 update 다.
     *
     * @param noticeMessageId 새 시도가 저장한 알림 줄
     * @return 새 시도. 번호는 늘린 뒤의 시도 수다
     * @throws ApiException {@code DELIVERY_NOT_RETRYABLE}. 바뀐 줄이 없을 때
     */
    @Transactional
    public DeliveryAttemptRef claimRetry(Long deliveryId, Long noticeMessageId, Instant now) {
        if (deliveries.claimRetry(deliveryId, RETRYABLE, noticeMessageId, now) == 0) {
            throw notRetryable();
        }
        return deliveries
            .findById(deliveryId)
            .map(delivery -> new DeliveryAttemptRef(delivery.id(), delivery.attemptCount()))
            .orElseThrow(ResultDeliveryRecorder::notRetryable);
    }


    /**
     * 그 대화의 묶음마다 마지막 시도가 저장한 마지막 알림 줄을 열쇠로, 묶음의 번호와 상태를 낸다. 이력 API 가 알림 줄 아래에
     * 그린다.
     *
     * <p>마지막 시도에 알림 줄이 없으면 그 묶음은 빠진다. 질의는 한 번이다.
     */
    @Transactional(readOnly = true)
    public Map<Long, DeliveryState> statesByNotice(Long conversationId) {
        Map<Long, DeliveryState> states = new HashMap<>();
        deliveries.findByConversationId(conversationId).stream()
            .filter(delivery -> delivery.lastAttemptNoticeMessageId() != null)
            .forEach(delivery -> states.put(
                delivery.lastAttemptNoticeMessageId(), new DeliveryState(delivery.id(), delivery.status())));
        return states;
    }


    /**
     * 그 대화의 위임 결과 알림 줄 번호마다 그 줄이 전한 위임 실행 번호다. 묶음의 항목 순서다. 이력 API 가 결과 도착 줄의 이름표를
     * 그리는 데 쓴다.
     *
     * <p>주인 확인은 부르는 쪽이 마친 번호로 부른다. 실행 번호로 읽지 못하는 항목은 뺀다.
     */
    public Map<Long, List<Long>> delegationResultsByNotice(Long conversationId) {
        Map<Long, List<Long>> results = new HashMap<>();
        for (ResultDelivery delivery : deliveries.findByConversationId(conversationId)) {
            if (delivery.delegationNoticeMessageId() == null) {
                continue;
            }
            List<Long> executionIds = new ArrayList<>();
            for (DeliveryItemRef item : readItems(delivery.id(), delivery.itemsJson())) {
                if (!DELEGATION_SOURCE.equals(item.source())) {
                    continue;
                }
                try {
                    executionIds.add(Long.valueOf(item.resultKey()));
                } catch (NumberFormatException ex) {
                    log.warn("실행 번호로 읽지 못하는 위임 결과 항목을 뺀다 deliveryId={}", delivery.id());
                }
            }
            results.put(delivery.delegationNoticeMessageId(), executionIds);
        }
        return results;
    }


    /** 시도에 부모 turn 의 실행 줄을 잇는다. 이미 이어졌거나 새 시도가 덮은 시도는 그대로 둔다. */
    @Transactional
    public void attachExecution(DeliveryAttemptRef attempt, Long executionId) {
        deliveries.attachExecution(attempt.deliveryId(), attempt.attemptNo(), executionId);
    }


    /**
     * 도는 중인 시도를 닫고 그 묶음의 상태를 시도의 끝에 맞춘다. 이미 닫혔거나 새 시도가 덮은 시도면 아무것도 하지 않는다.
     *
     * @throws IllegalArgumentException {@code status} 가 {@code RUNNING} 일 때
     */
    @Transactional
    public void finish(DeliveryAttemptRef attempt, DeliveryAttemptStatus status, String errorCode) {
        close(attempt.deliveryId(), attempt.attemptNo(), status, errorCode);
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
        deliveries.findFirstByLastAttemptExecutionIdAndLastAttemptStatus(execution.id(), DeliveryAttemptStatus.RUNNING)
            .ifPresent(delivery -> close(delivery.id(), delivery.attemptCount(), status, errorCodeOf(execution, status)));
    }


    /**
     * 그 시각 전에 시작해 {@code RUNNING} 으로 남은 마지막 시도를 닫고 닫은 수를 돌려준다. 이전 프로세스가 남긴 시도를 기동할 때
     * 닫는다.
     *
     * <p>묶음마다 트랜잭션을 따로 연다. 실행 줄이 없거나 지워졌으면 {@code FAILED} 와 {@link #INTERRUPTED} 로, 실행 줄이
     * 끝났으면 {@link #finishByExecution} 과 같은 규칙으로 닫는다. 실행 줄이 아직 {@code RUNNING} 이면 건너뛴다. 기동
     * 정리가 그 줄을 정할 때 함께 닫는다. 한 시도를 닫다 실패하면 경고 로그만 남기고 다음 시도로 간다.
     */
    public int closeLeftovers(Instant startedBefore) {
        int closed = 0;
        for (ResultDelivery delivery : deliveries.findByLastAttemptStatusAndLastAttemptStartedAtBefore(
            DeliveryAttemptStatus.RUNNING, startedBefore)) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> closeLeftover(delivery)))) {
                    closed++;
                }
            } catch (RuntimeException ex) {
                log.warn("기동 전에 남은 전달 시도를 닫지 못했다 deliveryId={} attemptNo={}",
                    delivery.id(), delivery.attemptCount(), ex);
            }
        }
        return closed;
    }


    private boolean closeLeftover(ResultDelivery delivery) {
        AgentExecution execution = delivery.lastAttemptExecutionId() == null
            ? null
            : executions.findById(delivery.lastAttemptExecutionId()).orElse(null);
        if (execution == null) {
            return close(delivery.id(), delivery.attemptCount(), DeliveryAttemptStatus.FAILED, INTERRUPTED);
        }
        DeliveryAttemptStatus status = attemptStatusOf(execution);
        if (status == null) {
            return false;
        }
        return close(delivery.id(), delivery.attemptCount(), status, errorCodeOf(execution, status));
    }


    /** 도는 중인 마지막 시도를 닫고 묶음 상태를 맞춘다. 실제로 닫았으면 참이다. 트랜잭션 안에서 부른다. */
    private boolean close(Long deliveryId, int attemptNo, DeliveryAttemptStatus status, String errorCode) {
        if (status == DeliveryAttemptStatus.RUNNING) {
            throw new IllegalArgumentException("an attempt cannot finish as RUNNING");
        }
        return deliveries.finishAttempt(
            deliveryId, attemptNo, status, status.deliveryStatus(), errorCode, clock.instant()) == 1;
    }


    /** 항목을 {@code items_json} 버전 1 로 쓴다. */
    private static String itemsJson(List<DeliveryItemRef> items) {
        return JSON.writeValueAsString(new StoredItems(
            ITEMS_VERSION, items.stream().map(item -> new StoredItem(item.source(), item.resultKey())).toList()));
    }


    /** 버전이 다르거나 모양이 깨졌으면 경고 로그를 남기고 빈 목록이다. */
    private static List<DeliveryItemRef> readItems(Long deliveryId, String json) {
        try {
            StoredItems stored = JSON.readValue(json, StoredItems.class);
            if (stored == null || stored.version() != ITEMS_VERSION || stored.items() == null) {
                log.warn("전달 묶음의 항목을 읽지 못했다 deliveryId={}", deliveryId);
                return List.of();
            }
            return stored.items().stream()
                .filter(item -> item != null && item.source() != null && item.resultKey() != null)
                .map(item -> new DeliveryItemRef(item.source(), item.resultKey()))
                .toList();
        } catch (JacksonException ex) {
            log.warn("전달 묶음의 항목을 읽지 못했다 deliveryId={}", deliveryId, ex);
            return List.of();
        }
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
    public static ApiException notRetryable() {
        return new ApiException(ErrorCode.DELIVERY_NOT_RETRYABLE, "this result delivery cannot be retried now");
    }


    /** {@code items_json} 의 저장 모양이다. */
    private record StoredItems(int version, List<StoredItem> items) {
    }

    /** {@code items_json} 의 항목 하나다. */
    private record StoredItem(String source, String resultKey) {
    }
}
