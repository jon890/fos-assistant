package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorConnection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorConnectionRepository extends JpaRepository<ConnectorConnection, Long> {

    Optional<ConnectorConnection> findByUserIdAndConnectorId(Long userId, String connectorId);

    List<ConnectorConnection> findByUserId(Long userId);

    List<ConnectorConnection> findByUserIdIn(List<Long> userIds);
}
