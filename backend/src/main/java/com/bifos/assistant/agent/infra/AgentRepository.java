package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.Agent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    Optional<Agent> findByCode(String code);
    List<Agent> findByEnabledTrueOrderByCodeAsc();
}
