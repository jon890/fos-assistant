package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorBinding;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorBindingRepository extends JpaRepository<ConnectorBinding, Long> {

    /** 도구 호출 판정이 트랜잭션 밖에서 연결 상태와 에이전트의 profile 을 읽으므로 둘을 한 번에 읽는다. */
    @EntityGraph(attributePaths = {"agent", "connection"})
    List<ConnectorBinding> findByAgentId(Long agentId);

    List<ConnectorBinding> findByConnectionId(Long connectionId);

    Optional<ConnectorBinding> findByAgentIdAndConnectionId(Long agentId, Long connectionId);

    boolean existsByAgentId(Long agentId);

    /** 관리자 목록이 같은 그룹 사용자의 바인딩을 한 번에 읽는다. */
    List<ConnectorBinding> findByConnectionUserIdIn(Collection<Long> userIds);
}
