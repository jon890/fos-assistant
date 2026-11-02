package com.bifos.assistant.chat.delivery.infra;

import com.bifos.assistant.chat.delivery.domain.ResultDelivery;
import com.bifos.assistant.chat.delivery.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.chat.delivery.domain.type.DeliveryStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 트랜잭션은 부르는 서비스가 연다. 고치는 쿼리는 트랜잭션 밖에서 부르면 실패한다.
 *
 * <p>마지막 시도는 {@code (id, attempt_count)} 로 가리킨다. 시도를 고치는 쿼리는 그 번호가 아직 마지막 시도일 때만 바꾼다.
 */
public interface ResultDeliveryRepository extends JpaRepository<ResultDelivery, Long> {

    /** 그 대화의 묶음이다. 다른 대화의 묶음이면 빈 값이다. */
    Optional<ResultDelivery> findByIdAndConversationId(Long id, Long conversationId);


    /** 그 대화의 묶음들이다. 순서는 정하지 않는다. */
    List<ResultDelivery> findByConversationId(Long conversationId);


    /**
     * 묶음이 그 상태들 가운데 하나일 때만 {@code DELIVERING} 으로 바꾸고 시도 수를 하나 늘려, 마지막 시도 칸을 도는 중인 새
     * 시도로 덮는다. 바뀐 행 수를 돌려준다.
     *
     * <p>다시 전달을 시작하는 자리다. 같은 묶음을 두 요청이 함께 바꾸려 해도 한쪽만 1 을 받는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ResultDelivery d set d.status = com.bifos.assistant.chat.delivery.domain.type.DeliveryStatus.DELIVERING,"
        + " d.attemptCount = d.attemptCount + 1, d.updatedAt = :at,"
        + " d.lastAttemptStatus = com.bifos.assistant.chat.delivery.domain.type.DeliveryAttemptStatus.RUNNING,"
        + " d.lastAttemptExecutionId = null, d.lastAttemptNoticeMessageId = :noticeMessageId,"
        + " d.lastAttemptErrorCode = null, d.lastAttemptStartedAt = :at, d.lastAttemptFinishedAt = null"
        + " where d.id = :id and d.status in :from")
    int claimRetry(
        @Param("id") Long id,
        @Param("from") Collection<DeliveryStatus> from,
        @Param("noticeMessageId") Long noticeMessageId,
        @Param("at") Instant at);


    /** 그 번호가 마지막 시도이고 아직 실행 줄이 없을 때만 잇는다. 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ResultDelivery d set d.lastAttemptExecutionId = :executionId"
        + " where d.id = :id and d.attemptCount = :attemptNo and d.lastAttemptExecutionId is null")
    int attachExecution(
        @Param("id") Long id, @Param("attemptNo") int attemptNo, @Param("executionId") Long executionId);


    /**
     * 그 번호가 마지막 시도이고 도는 중일 때만 시도를 닫고 묶음의 상태를 함께 바꾼다. 이미 닫혔거나 새 시도가 덮었으면 0 을
     * 돌려준다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ResultDelivery d set d.lastAttemptStatus = :attemptStatus, d.lastAttemptErrorCode = :errorCode,"
        + " d.lastAttemptFinishedAt = :at, d.status = :status, d.updatedAt = :at"
        + " where d.id = :id and d.attemptCount = :attemptNo"
        + " and d.lastAttemptStatus = com.bifos.assistant.chat.delivery.domain.type.DeliveryAttemptStatus.RUNNING")
    int finishAttempt(
        @Param("id") Long id,
        @Param("attemptNo") int attemptNo,
        @Param("attemptStatus") DeliveryAttemptStatus attemptStatus,
        @Param("status") DeliveryStatus status,
        @Param("errorCode") String errorCode,
        @Param("at") Instant at);


    /** 마지막 시도가 그 상태로 그 시각 전에 시작한 묶음들이다. 기동할 때 이전 프로세스가 남긴 시도를 찾는다. */
    List<ResultDelivery> findByLastAttemptStatusAndLastAttemptStartedAtBefore(
        DeliveryAttemptStatus status, Instant before);


    /** 마지막 시도가 그 실행 줄을 이었고 그 상태인 묶음 하나다. */
    Optional<ResultDelivery> findFirstByLastAttemptExecutionIdAndLastAttemptStatus(
        Long executionId, DeliveryAttemptStatus status);


    /**
     * 그 사용자의 지우지 않은 대화에서 {@code FAILED} 이고 {@code since} 뒤에 바뀐 묶음을 최근 순으로 읽는다.
     *
     * <p>묶음에는 사용자 칸이 없어 대화의 {@code user_id} 로 잇는다. 먼저 알리기의 실패 카드가 읽는다.
     */
    @Query("select d from ResultDelivery d"
        + " where d.status = com.bifos.assistant.chat.delivery.domain.type.DeliveryStatus.FAILED"
        + " and d.updatedAt >= :since"
        + " and exists (select c.id from Conversation c"
        + " where c.id = d.conversationId and c.userId = :userId and c.deletedAt is null)"
        + " order by d.id desc")
    List<ResultDelivery> findFailedOfUser(@Param("userId") Long userId, @Param("since") Instant since);
}
