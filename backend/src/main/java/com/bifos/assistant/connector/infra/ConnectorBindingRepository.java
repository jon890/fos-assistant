package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.DueBinding;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
    @Query("select new com.bifos.assistant.connector.domain.DueBinding(b.id, b.agent.id, b.connection.userId)"
            + " from ConnectorBinding b"
            + " where b.applyDueAt <= :now and b.restartRequired = false"
            + " order by b.applyDueAt")
    List<DueBinding> findApplyDue(@Param("now") Instant now);
}
