package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.type.DeliveryStatus;
import java.time.Instant;
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
}
