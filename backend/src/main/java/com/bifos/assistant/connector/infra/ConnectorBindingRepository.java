package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorBinding;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorBindingRepository extends JpaRepository<ConnectorBinding, Long> {

    List<ConnectorBinding> findByAgentId(Long agentId);

    List<ConnectorBinding> findByConnectionId(Long connectionId);

    Optional<ConnectorBinding> findByAgentIdAndConnectionId(Long agentId, Long connectionId);

    boolean existsByAgentId(Long agentId);

    /** 관리자 목록이 같은 그룹 사용자의 바인딩을 한 번에 읽는다. */
    List<ConnectorBinding> findByConnectionUserIdIn(Collection<Long> userIds);
}
