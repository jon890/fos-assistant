package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.AccountbookConnection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountbookConnectionRepository extends JpaRepository<AccountbookConnection, Long> {
    Optional<AccountbookConnection> findByAgentId(Long agentId);
    List<AccountbookConnection> findByUserIdIn(List<Long> userIds);
}
