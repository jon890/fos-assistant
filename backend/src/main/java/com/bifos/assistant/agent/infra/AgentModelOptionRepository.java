package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.AgentModelOption;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentModelOptionRepository extends JpaRepository<AgentModelOption, Long> {

    List<AgentModelOption> findByAgentIdOrderByRankAsc(Long agentId);

    void deleteByAgentId(Long agentId);
}
