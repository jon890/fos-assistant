package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 승인 이력에 속한 실행 내용이다. 같은 의도 조회는 일반 인덱스를 사용한다. */
public interface ConnectorActionExecutionRepository extends JpaRepository<ConnectorActionExecution, Long> {
    List<ConnectorActionExecution> findByRequestKeyOrderByActionIdAsc(String requestKey);


    /** keyed hash 이전의 평문 request_key 줄이 남았는지 본다. 남았으면 같은 의도 차단이 그 줄을 놓치므로 금융 관문을 닫는다. */
    @Query("select count(e) > 0 from ConnectorActionExecution e where e.requestKey not like 'k1%'")
    boolean existsUnkeyedRequestKey();


    /** 사용자와 승인 줄을 잠근 뒤 실행 내용을 마지막으로 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ConnectorActionExecution e where e.actionId = :actionId")
    Optional<ConnectorActionExecution> findByActionIdForUpdate(@Param("actionId") Long actionId);
}
