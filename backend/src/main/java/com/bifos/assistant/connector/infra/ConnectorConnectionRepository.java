package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorConnection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorConnectionRepository extends JpaRepository<ConnectorConnection, Long> {

    Optional<ConnectorConnection> findByUserIdAndConnectorId(Long userId, String connectorId);

    /** 목록이 카탈로그에서 빠진 연결의 이름으로 에이전트 이름을 쓰므로 에이전트를 한 번에 읽는다. */
    @EntityGraph(attributePaths = "agent")
    List<ConnectorConnection> findByUserId(Long userId);

    /** 관리자 목록이 에이전트 코드를 함께 보이므로 에이전트를 한 번에 읽는다. */
    @EntityGraph(attributePaths = "agent")
    List<ConnectorConnection> findByUserIdIn(List<Long> userIds);
}
