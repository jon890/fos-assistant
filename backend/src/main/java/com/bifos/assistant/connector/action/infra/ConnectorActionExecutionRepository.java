package com.bifos.assistant.connector.action.infra;

import com.bifos.assistant.connector.action.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

/** 승인 이력에 속한 실행 내용이다. 같은 의도 조회는 일반 인덱스를 사용한다. */
public interface ConnectorActionExecutionRepository extends JpaRepository<ConnectorActionExecution, Long> {
    List<ConnectorActionExecution> findByRequestKeyOrderByActionIdAsc(String requestKey);


    /**
     * 지금 protocol 과 지금 key 로 만들지 않은 열린 승인 줄이 있는지 본다.
     *
     * <p>keyed hash 이전 protocol 의 줄과 이전 key 로 만든 줄은 request_key 가 지금 계산과 달라 같은 의도 차단이 놓친다.
     * 그런 줄이 열려 있으면 금융 관문을 닫는다. 끝난 줄은 보지 않는다.
     * 모든 금융 줄을 보면 첫 실제 호출이 남긴 줄이나 key 교체 전의 끝난 줄 때문에 그 뒤 재기동마다 관문이 닫힌다.
     */
    @Query("select count(e) > 0 from ConnectorActionExecution e, ConnectorAction a"
        + " where a.id = e.actionId and a.status in :statuses"
        + " and (e.protocol <> :protocol or e.requestKey not like concat(:prefix, '%'))")
    boolean existsOpenOutside(
        @Param("protocol") String protocol, @Param("prefix") String prefix, @Param("statuses") Collection<ActionStatus> statuses);


    /** 사용자와 승인 줄을 잠근 뒤 실행 내용을 마지막으로 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ConnectorActionExecution e where e.actionId = :actionId")
    Optional<ConnectorActionExecution> findByActionIdForUpdate(@Param("actionId") Long actionId);


    /** 승인 줄과 같은 purge 트랜잭션에서 실행 본문과 key만 지우고 해시·ticket·연결 참조는 남긴다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ConnectorActionExecution e
            set e.executionArgsJson = null, e.summaryJson = null, e.scopeJson = null, e.contentKeyId = null
            where e.actionId in :ids
            """)
    int eraseBodiesOf(@Param("ids") Collection<Long> ids);
}
