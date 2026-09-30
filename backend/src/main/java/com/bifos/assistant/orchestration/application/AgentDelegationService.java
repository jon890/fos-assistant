package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hermes 가 MCP {@code agent_*} 도구로 다른 에이전트를 부를 때의 경계를 판정한다(ADR-017).
 *
 * <p>요청자와 기준 실행은 호출하는 쪽이 정해 넘긴 값만 쓴다. 토큰, profile, 최근 실행, 대화로 추측하지 않는다.
 * 이 패키지는 {@code mcp} 의 타입을 import 하지 않는다. {@code mcp} 가 요청자와 origin 실행을 풀어 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class AgentDelegationService {

    private final AgentService agents;
    private final AgentExecutionRepository executions;

    /** 요청자가 쓸 수 있고 켜진 에이전트다. 같은 profile 을 여럿이 써도 요청자마다 다르다. */
    @Transactional(readOnly = true)
    public List<Agent> list(CurrentUser user) {
        return agents.readableBy(user);
    }

    /**
     * 요청자가 물을 수 있는 위임 실행 하나를 읽는다.
     *
     * <p>물을 수 있는 실행은 {@link #canQuery} 가 정한다. 아니면 없는 실행과 같게 빈 값이다. 남의 실행이 있는지 알리지 않는다.
     * origin 실행은 끝났어도 된다. 부모 turn 이 끝난 뒤에도 Hermes 하위 에이전트가 부르기 때문이다(ADR-037).
     */
    @Transactional(readOnly = true)
    public Optional<AgentExecution> status(CurrentUser user, AgentExecution origin, Long executionId) {
        return executions.findById(executionId).filter(execution -> canQuery(user, origin, execution));
    }

    /**
     * 부르는 쪽이 이 실행을 물을 수 있는지 판정한다(ADR-017 「도구 넷과 한도」).
     *
     * <p>요청자의 실행이고 위임으로 만든 실행({@code delegation_key} 가 있음)이어야 한다. 범위는 origin 실행의 대화다.
     * 대화의 turn 마다 뿌리 실행이 새로 생기므로, 앞 turn 에서 맡긴 실행을 다음 turn 에서 물으려면 나무가 아니라 대화로
     * 견줘야 한다. origin 실행에 대화가 없으면 같은 실행 나무로 견준다. 같은 사용자의 다른 대화는 물을 수 없다.
     */
    private boolean canQuery(CurrentUser user, AgentExecution origin, AgentExecution execution) {
        if (!Objects.equals(execution.userId(), user.id()) || execution.delegationKey() == null) {
            return false;
        }
        if (origin.conversationId() != null) {
            return Objects.equals(execution.conversationId(), origin.conversationId());
        }
        return Objects.equals(execution.treeRootId(), origin.treeRootId());
    }
}
