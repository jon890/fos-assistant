package com.bifos.assistant.mcp.infra;

import com.bifos.assistant.mcp.domain.AgentToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTokenRepository extends JpaRepository<AgentToken, Long> {
    Optional<AgentToken> findByTokenHash(String tokenHash);
}
