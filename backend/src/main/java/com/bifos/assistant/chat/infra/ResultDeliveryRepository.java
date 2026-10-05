package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.type.DeliveryStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 트랜잭션은 부르는 서비스가 연다. 고치는 쿼리는 트랜잭션 밖에서 부르면 실패한다. */
public interface ResultDeliveryRepository extends JpaRepository<ResultDelivery, Long> {

    /** 묶음의 상태를 바꾼다. 시도의 상태를 바꾸는 트랜잭션 안에서 부른다. 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ResultDelivery d set d.status = :status, d.updatedAt = :at where d.id = :id")
    int changeStatus(@Param("id") Long id, @Param("status") DeliveryStatus status, @Param("at") Instant at);

    /** 그 대화의 묶음이다. 다른 대화의 묶음이면 빈 값이다. */
    Optional<ResultDelivery> findByIdAndConversationId(Long id, Long conversationId);

    /** 그 대화의 묶음들이다. 순서는 정하지 않는다. */
    List<ResultDelivery> findByConversationId(Long conversationId);

    /**
     * 묶음이 그 상태들 가운데 하나일 때만 {@code DELIVERING} 으로 바꾸고 시도 수를 하나 늘린다. 바뀐 행 수를 돌려준다.
     *
     * <p>다시 전달을 시작하는 자리다. 같은 묶음을 두 요청이 함께 바꾸려 해도 한쪽만 1 을 받는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ResultDelivery d set d.status = com.bifos.assistant.chat.domain.type.DeliveryStatus.DELIVERING,"
            + " d.attemptCount = d.attemptCount + 1, d.updatedAt = :at"
            + " where d.id = :id and d.status in :from")
    int claimRetry(@Param("id") Long id, @Param("from") Collection<DeliveryStatus> from, @Param("at") Instant at);

    /**
     * 그 사용자의 지우지 않은 대화에서 {@code FAILED} 이고 {@code since} 뒤에 바뀐 묶음을 최근 순으로 읽는다.
     *
     * <p>묶음에는 사용자 칸이 없어 대화의 {@code user_id} 로 잇는다. 먼저 알리기의 실패 카드가 읽는다.
     */
    @Query("select d from ResultDelivery d"
            + " where d.status = com.bifos.assistant.chat.domain.type.DeliveryStatus.FAILED"
            + " and d.updatedAt >= :since"
            + " and exists (select c.id from Conversation c"
            + " where c.id = d.conversationId and c.userId = :userId and c.deletedAt is null)"
            + " order by d.id desc")
    List<ResultDelivery> findFailedOfUser(@Param("userId") Long userId, @Param("since") Instant since);
}
