package com.bifos.assistant.mcp.infra;

import com.bifos.assistant.mcp.domain.AgentToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTokenRepository extends JpaRepository<AgentToken, Long> {
    Optional<AgentToken> findByTokenHash(String tokenHash);

    /** 그 profile 에 묶였고 아직 폐기하지 않은 토큰이다. */
    List<AgentToken> findByProfileNameAndRevokedAtIsNull(String profileName);
}
