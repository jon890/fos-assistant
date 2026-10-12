package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 승인 이력에 속한 실행 내용이다. 같은 의도 조회는 일반 인덱스를 사용한다. */
public interface ConnectorActionExecutionRepository extends JpaRepository<ConnectorActionExecution, Long> {
    List<ConnectorActionExecution> findByRequestKeyOrderByActionIdAsc(String requestKey);


    /**
     * 그 protocol 로 저장한 줄 가운데 아직 열린 승인 줄이 있는지 본다.
     *
     * <p>keyed hash 이전 protocol 의 열린 줄은 같은 의도 차단이 놓치므로 금융 관문을 닫는다. 끝난 줄은 보지 않는다.
     * 모든 금융 줄을 보면 첫 실제 호출이 남긴 줄 때문에 그 뒤 재기동마다 관문이 닫힌다.
     */
    @Query("select count(e) > 0 from ConnectorActionExecution e, ConnectorAction a"
        + " where a.id = e.actionId and e.protocol = :protocol and a.status in :statuses")
    boolean existsOpenWithProtocol(@Param("protocol") String protocol, @Param("statuses") Collection<ActionStatus> statuses);


    /** 사용자와 승인 줄을 잠근 뒤 실행 내용을 마지막으로 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ConnectorActionExecution e where e.actionId = :actionId")
    Optional<ConnectorActionExecution> findByActionIdForUpdate(@Param("actionId") Long actionId);
}
