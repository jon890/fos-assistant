package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorAction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorActionRepository extends JpaRepository<ConnectorAction, Long> {

    Optional<ConnectorAction> findByDedupeKey(String dedupeKey);
}
