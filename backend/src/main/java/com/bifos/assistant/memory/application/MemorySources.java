package com.bifos.assistant.memory.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.memory.application.model.MemorySource;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.ExecutionAgentQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트가 남긴 기억마다 남긴 에이전트를 찾는다.
 *
 * <p>기억이 가진 것은 남긴 실행의 번호뿐이다. 실행에서 에이전트를 찾고, 그 에이전트를 읽는 사용자가 볼 수 있을 때만
 * 이름을 싣는다. 볼 수 없는 비공개 에이전트의 이름이 기억 목록으로 새지 않게 하려는 것이다. 지운 에이전트는 이름 없이
 * 지웠다는 표시만 싣는다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemorySources {

    private final ExecutionAgentQuery executions;
    private final AgentService agents;

    /** 기억 번호별 출처다. 에이전트가 남기지 않은 기억은 맵에 없다. */
    public Map<Long, MemorySource> of(CurrentUser user, Collection<Memory> memories) {
        Map<Long, Long> agentByExecution = executions.agentIdsOf(
                memories.stream().map(Memory::proposedByExecutionId).toList());
        Map<Long, Agent> agentById = agents.byIds(agentByExecution.values());
        Map<Long, MemorySource> result = new HashMap<>();
        for (Memory memory : memories) {
            if (memory.proposedByExecutionId() == null) {
                continue;
            }
            Agent agent = agentById.get(agentByExecution.get(memory.proposedByExecutionId()));
            result.put(memory.id(), describe(user, agent));
        }
        return result;
    }

    private static MemorySource describe(CurrentUser user, Agent agent) {
        if (agent == null) {
            return MemorySource.UNNAMED;
        }
        if (agent.isDeleted()) {
            return new MemorySource(null, true);
        }
        if (!agent.isReadableBy(user.id())) {
            return MemorySource.UNNAMED;
        }
        return new MemorySource(agent.name(), false);
    }
}
