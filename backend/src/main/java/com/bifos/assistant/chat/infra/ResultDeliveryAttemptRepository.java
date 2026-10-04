package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ResultDeliveryAttempt;
import com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 트랜잭션은 부르는 서비스가 연다. 고치는 쿼리는 트랜잭션 밖에서 부르면 실패한다. */
public interface ResultDeliveryAttemptRepository extends JpaRepository<ResultDeliveryAttempt, Long> {

    /** 아직 실행 줄이 없는 시도에만 잇는다. 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ResultDeliveryAttempt a set a.executionId = :executionId"
            + " where a.id = :id and a.executionId is null")
    int attachExecution(@Param("id") Long id, @Param("executionId") Long executionId);

    /** 도는 중인 시도만 닫는다. 이미 닫힌 시도면 0 을 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ResultDeliveryAttempt a set a.status = :status, a.errorCode = :errorCode, a.finishedAt = :at"
            + " where a.id = :id and a.status = com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus.RUNNING")
    int finish(
            @Param("id") Long id,
            @Param("status") DeliveryAttemptStatus status,
            @Param("errorCode") String errorCode,
            @Param("at") Instant at);

    /** 그 상태로 그 시각 전에 시작한 시도들이다. 기동할 때 이전 프로세스가 남긴 시도를 찾는다. */
    List<ResultDeliveryAttempt> findByStatusAndStartedAtBefore(DeliveryAttemptStatus status, Instant before);

    /** 그 실행 줄을 이은 시도 가운데 그 상태인 것 하나다. */
    Optional<ResultDeliveryAttempt> findFirstByExecutionIdAndStatus(Long executionId, DeliveryAttemptStatus status);
}
