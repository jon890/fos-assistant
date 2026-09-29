package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentService {
    private final AgentRepository agents;

    public List<Agent> readableBy(CurrentUser user) {
        return agents.findByEnabledTrueOrderByCodeAsc().stream()
                .filter(agent -> agent.isReadableBy(user.id()))
                .toList();
    }

    public Agent requireReadable(CurrentUser user, String code) {
        Agent agent = agents.findByCode(code)
                .orElseThrow(() -> notFound());
        if (!agent.isReadableBy(user.id())) {
            throw notFound();
        }
        return agent;
    }

    /** 대화를 시작하거나 그 에이전트의 모델을 고를 수 있는 에이전트다. 요청자가 읽을 수 있고 켜져 있어야 한다. */
    public Agent requireStartable(CurrentUser user, String code) {
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "an agent is required");
        }
        Agent agent = requireReadable(user, code);
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        return agent;
    }

    /** 쓰기 전에 행 잠금을 잡고, 잠금을 잡은 뒤의 접근 범위를 다시 확인한다. */
    public Agent requireReadableForUpdate(CurrentUser user, String code) {
        Agent locked = agents.findByCodeForUpdate(code).orElseThrow(() -> notFound());
        if (!locked.isReadableBy(user.id())) throw notFound();
        return locked;
    }

    /**
     * 요청자가 이 에이전트의 성격과 소개, 추천 질문을 고칠 수 있는가.
     *
     * <p>주인과 {@code ADMIN} 만 고친다. 그룹이 함께 쓰는 에이전트는 여럿이 함께 읽는 글이라 주인이 없고
     * {@code ADMIN} 만 통과한다. 고칠 수 있는지 판정하는 곳은 모두 이 메서드를 부른다. 판정을 복사하면
     * 한쪽만 바뀌어 성격은 못 고치는데 추천 질문은 고치는 에이전트가 생긴다.
     */
    public boolean isEditableBy(CurrentUser user, Agent agent) {
        return user.isAdmin() || Objects.equals(agent.ownerUserId(), user.id());
    }

    public Agent requireById(Long id) {
        return agents.findById(id).orElseThrow(() -> notFound());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
    }
}
