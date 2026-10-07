package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.ExecutionAgentRef;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 번호들이 어느 에이전트로 돌았는지 한 번에 읽는다.
 *
 * <p>기억 목록이 「누가 남겼는지」 를 보이려고 부른다. 다른 패키지가 이 패키지의 저장소를 바로 import 하지 않게
 * 읽기 메서드만 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExecutionAgentQuery {

    private final AgentExecutionRepository executions;

    /** 실행 번호별 에이전트 번호다. 행이 없거나 에이전트에 묶이지 않은 실행은 맵에 없다. */
    public Map<Long, Long> agentIdsOf(Collection<Long> executionIds) {
        List<Long> present = executionIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, Long> result = new HashMap<>();
        if (present.isEmpty()) {
            return result;
        }
        for (ExecutionAgentRef ref : executions.findAgentRefs(present)) {
            if (ref.agentId() != null) {
                result.put(ref.executionId(), ref.agentId());
            }
        }
        return result;
    }
}
