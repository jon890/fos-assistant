package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorConnection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorConnectionRepository extends JpaRepository<ConnectorConnection, Long> {

    Optional<ConnectorConnection> findByUserIdAndConnectorId(Long userId, String connectorId);

    List<ConnectorConnection> findByUserId(Long userId);

    List<ConnectorConnection> findByUserIdIn(List<Long> userIds);

    /** 지운 에이전트를 정리할 때 그 에이전트를 가리키는 옛 칸을 비운다. 연결 줄은 사용자의 것이라 남긴다. 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ConnectorConnection c set c.legacyAgentId = null where c.legacyAgentId = :agentId")
    int clearAgentOf(@Param("agentId") Long agentId);
}
