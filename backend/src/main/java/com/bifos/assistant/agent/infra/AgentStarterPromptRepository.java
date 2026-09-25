package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.AgentStarterPrompt;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentStarterPromptRepository extends JpaRepository<AgentStarterPrompt, Long> {

    List<AgentStarterPrompt> findByAgentIdOrderByPositionAsc(Long agentId);

    /** 목록 경로가 에이전트 수만큼 질의하지 않게 여러 에이전트의 줄을 한 번에 읽는다. */
    List<AgentStarterPrompt> findByAgentIdInOrderByAgentIdAscPositionAsc(Collection<Long> agentIds);

    void deleteByAgentId(Long agentId);
}
