package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentModelOption;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트가 쓸 모델을 순위대로 고른다.
 *
 * <p>층이 둘이고 이 클래스는 위층만 맡는다. 같은 provider 안에서 계정을 돌려 쓰는 아래층은 Hermes 가
 * 이미 한다.
 */
@Service
@RequiredArgsConstructor
public class AgentModelSelector {

    private final AgentModelOptionRepository options;
    private final ProviderBlocklist blocklist;

    /** 이 에이전트의 모델 목록을 순위대로 준다. */
    public List<AgentModelOption> optionsOf(Agent agent) {
        return options.findByAgentIdOrderByRankAsc(agent.id());
    }

    /**
     * 이 에이전트가 지금 쓸 수 있는 모델을 순위대로 준다. 없으면 빈 목록이다.
     *
     * <p>막힌 provider 는 빠진다. 전부 막혔으면 빈 목록이 돌아가고 부르는 쪽이 Hermes 를 부르지 않는다.
     */
    public List<ModelOption> availableFor(Agent agent) {
        Set<String> blocked = blocklist.blockedProviders();
        return optionsOf(agent).stream()
                .map(AgentModelOption::toOption)
                .filter(option -> !blocked.contains(option.provider()))
                .toList();
    }

    /**
     * 목록 전체를 바꾼다. 한 줄씩 고치는 길을 두지 않는다.
     *
     * <p>순서가 뜻을 갖는 목록이라 부분 수정은 순서가 어긋날 자리를 만든다.
     *
     * @throws ApiException 목록이 비었을 때. 모델이 하나도 없는 에이전트는 실행할 수 없다
     */
    @Transactional
    public List<AgentModelOption> replace(Agent agent, List<ModelOption> replacement) {
        if (replacement == null || replacement.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "an agent needs at least one model");
        }
        for (ModelOption option : replacement) {
            if (!option.complete()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "a model needs both a provider and a model");
            }
        }
        options.deleteByAgentId(agent.id());
        options.flush();
        int rank = 1;
        for (ModelOption option : replacement) {
            options.save(AgentModelOption.of(agent.id(), rank++, option));
        }
        return optionsOf(agent);
    }

    /** 1순위 한 줄을 만든다. 에이전트를 새로 등록할 때 쓴다. */
    public AgentModelOption seedFirst(Agent agent, ModelOption option) {
        return options.save(AgentModelOption.of(agent.id(), 1, option));
    }

    /**
     * 1순위의 모델과 provider 를 갱신한다.
     *
     * <p>profile 의 기본 모델이 바뀐 것을 관리자가 화면에서 따라잡는 길이다. 1순위가 없으면 만든다.
     *
     * @return 값이 실제로 바뀌었는가
     */
    @Transactional
    public boolean syncFirst(Agent agent, ModelOption option) {
        List<AgentModelOption> current = optionsOf(agent);
        if (current.isEmpty()) {
            seedFirst(agent, option);
            return true;
        }
        AgentModelOption first = current.getFirst();
        boolean changed = !first.toOption().equals(option);
        first.change(option);
        options.save(first);
        return changed;
    }
}
