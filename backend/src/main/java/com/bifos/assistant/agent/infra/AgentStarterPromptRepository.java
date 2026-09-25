package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.AgentStarterPrompt;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentStarterPromptRepository extends JpaRepository<AgentStarterPrompt, Long> {

    List<AgentStarterPrompt> findByAgentIdOrderByPositionAsc(Long agentId);

    /** 목록 경로가 에이전트 수만큼 질의하지 않게 여러 에이전트의 줄을 한 번에 읽는다. */
    List<AgentStarterPrompt> findByAgentIdInOrderByAgentIdAscPositionAsc(Collection<Long> agentIds);

    /**
     * 그 에이전트의 줄을 한 문장으로 모두 지운다.
     *
     * <p>줄을 먼저 읽고 하나씩 지우면, 앞선 요청이 그사이 지우고 새로 넣은 줄을 보지 못해 이미 없는 줄을
     * 지우려다 실패한다. 한 문장으로 지우면 그 시점에 저장된 줄을 지운다.
     */
    @Modifying
    @Query("delete from AgentStarterPrompt p where p.agentId = :agentId")
    int deleteByAgentId(@Param("agentId") Long agentId);
}
