package com.bifos.assistant.connector.connection.infra;

import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.DetachTarget;
import com.bifos.assistant.connector.connection.domain.DueBinding;
import com.bifos.assistant.connector.connection.domain.EventWatchTarget;
import com.bifos.assistant.connector.connection.domain.ReadyBinding;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorBindingRepository extends JpaRepository<ConnectorBinding, Long> {

    /** 도구 호출 판정이 트랜잭션 밖에서 연결 상태와 에이전트의 profile 을 읽으므로 둘을 한 번에 읽는다. */
    @EntityGraph(attributePaths = {"agent", "connection"})
    List<ConnectorBinding> findByAgentId(Long agentId);


    List<ConnectorBinding> findByConnectionId(Long connectionId);


    Optional<ConnectorBinding> findByAgentIdAndConnectionId(Long agentId, Long connectionId);


    boolean existsByAgentId(Long agentId);


    /** 관리자 목록이 같은 그룹 사용자의 바인딩을 한 번에 읽는다. */
    List<ConnectorBinding> findByConnectionUserIdIn(Collection<Long> userIds);


    /**
     * 반영 예정 시각이 지났고 재시작 대기가 아닌 바인딩의 번호들이다(ADR-20261007 / connector-live-reload).
     *
     * <p>재시작 대기 바인딩은 관리자 반영 완료가 맡는다. JPQL 생성자 식이 전체 이름을 요구하므로 텍스트 블록 대신 문자열로 둔다.
     * 문자열 안의 전체 이름은 Checkstyle 의 {@code fullyQualifiedName} 대상이 아니다.
     */
    @Query("select new com.bifos.assistant.connector.connection.domain.DueBinding(b.id, b.agent.id, b.connection.userId)"
        + " from ConnectorBinding b"
        + " where b.applyDueAt <= :now and b.restartRequired = false"
        + " order by b.applyDueAt")
    List<DueBinding> findApplyDue(@Param("now") Instant now);


    /**
     * 그 에이전트들에 붙어 바인딩과 연결이 모두 그 상태인 줄이다. 에이전트 목록의 할 수 있는 일이 쓴다. 생성자 식을 문자열로 두는
     * 까닭은 {@link #findApplyDue} 와 같다.
     */
    @Query("select new com.bifos.assistant.connector.connection.domain.ReadyBinding(b.id, b.agent.id, b.connection.userId,"
        + " b.agent.hermesProfile, b.connection.connectorId)"
        + " from ConnectorBinding b"
        + " where b.agent.id in :agentIds and b.status = :status and b.connection.status = :connectionStatus"
        + " order by b.id")
    List<ReadyBinding> findUsableByAgentIds(
        @Param("agentIds") Collection<Long> agentIds,
        @Param("status") BindingStatus status,
        @Param("connectionStatus") ConnectionStatus connectionStatus);


    /**
     * 그 상태이고 번호가 {@code after} 보다 큰 바인딩을 번호 순으로 읽는다(ADR-20261009 / connector-install-drift).
     *
     * <p>정의 어긋남 점검이 {@code READY} 바인딩을 한 주기에 {@code page} 크기만큼 읽고 다음 주기는 그 뒤부터 읽는다. 생성자 식을
     * 문자열로 두는 까닭은 {@link #findApplyDue} 와 같다.
     */
    @Query("select new com.bifos.assistant.connector.connection.domain.ReadyBinding(b.id, b.agent.id, b.connection.userId,"
        + " b.agent.hermesProfile, b.connection.connectorId)"
        + " from ConnectorBinding b"
        + " where b.status = :status and b.id > :after"
        + " order by b.id")
    List<ReadyBinding> findReadyAfter(@Param("status") BindingStatus status, @Param("after") Long after, Pageable page);


    /**
     * 사건 감지를 켠 바인딩을 번호 순으로 읽는다(ADR-20261105 / event-watch). 생성자 식을 문자열로 두는 까닭은
     * {@link #findApplyDue} 와 같다.
     */
    @Query("select new com.bifos.assistant.connector.connection.domain.EventWatchTarget(b.id, b.agent.id, b.agent.code,"
        + " b.connection.userId, b.agent.hermesProfile, b.connection.connectorId, b.status, b.desiredEnabled,"
        + " b.connection.status, b.eventWatchState)"
        + " from ConnectorBinding b where b.eventWatchEnabled = true order by b.id")
    List<EventWatchTarget> findEventWatchTargets();


    /** 그 연결의 바인딩 줄을 모두 잠근다. 같은 연결에 사건 감지를 둘 켜지 않게 한다. 잠그는 순서는 번호 순이다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ConnectorBinding b where b.connection.id = :connectionId order by b.id")
    List<ConnectorBinding> lockByConnectionId(@Param("connectionId") Long connectionId);


    /** 바인딩 줄을 잠그고 사건 감지 상태만 다시 읽는다. 엔티티의 칸은 고치기에서 빠져 있어 DB 의 값을 직접 읽는다. */
    @Query(value = "SELECT event_watch_state FROM agent_connector_binding WHERE id = :id AND event_watch_enabled = TRUE FOR UPDATE",
        nativeQuery = true)
    Optional<String> lockEventWatchState(@Param("id") Long id);


    /** 사건 감지 상태만 덮어쓴다. 켜져 있는 줄만 바꾸고 {@code updated_at} 은 바꾸지 않는다. 바꾼 줄 수를 돌려준다. */
    @Modifying
    @Query(value = "UPDATE agent_connector_binding SET event_watch_state = :state WHERE id = :id AND event_watch_enabled = TRUE",
        nativeQuery = true)
    int writeEventWatchState(@Param("id") Long id, @Param("state") String state);


    /** 사건 감지를 켜거나 끈다. 끌 때는 상태도 비운다. {@code updated_at} 은 바꾸지 않는다. 바꾼 줄 수를 돌려준다. */
    @Modifying
    @Query(value = "UPDATE agent_connector_binding SET event_watch_enabled = :enabled, event_watch_state = :state WHERE id = :id",
        nativeQuery = true)
    int switchEventWatch(@Param("id") Long id, @Param("enabled") boolean enabled, @Param("state") String state);


    /** 지운 에이전트를 정리할 때 그 에이전트의 바인딩을 모두 지운다. 에이전트를 지울 때 이미 떼서 보통 없다. 지운 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ConnectorBinding b where b.agent.id = :agentId")
    int deleteAllOfAgent(@Param("agentId") Long agentId);


    /** 사용자 정리 때 그 연결의 바인딩마다 뗄 값을 읽는다. 연결의 칸 값은 읽지 않는다. JPQL 생성자 식이라 문자열로 둔다. */
    @Query("select new com.bifos.assistant.connector.connection.domain.DetachTarget("
        + "b.id, b.agent.hermesProfile, b.connection.connectorId)"
        + " from ConnectorBinding b where b.connection.id = :connectionId order by b.id")
    List<DetachTarget> findDetachTargets(@Param("connectionId") Long connectionId);


    /** 사용자 정리 때 profile 에서 뗀 바인딩 행 하나를 지운다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ConnectorBinding b where b.id = :id")
    int deleteDetached(@Param("id") Long id);
}
