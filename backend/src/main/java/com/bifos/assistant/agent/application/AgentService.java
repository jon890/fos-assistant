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

    /** 요청자가 고를 수 있는 에이전트 목록이다. 꺼진 것과 지운 것은 빠진다. */
    public List<Agent> readableBy(CurrentUser user) {
        return agents.findByEnabledTrueOrderByCodeAsc().stream()
                .filter(agent -> !agent.isDeleted())
                .filter(agent -> agent.isReadableBy(user.id()))
                .toList();
    }

    /** 요청자가 읽을 수 있는 에이전트다. 지운 에이전트는 없는 에이전트와 같게 {@code AGENT_NOT_FOUND} 다. */
    public Agent requireReadable(CurrentUser user, String code) {
        Agent agent = agents.findByCode(code)
                .orElseThrow(() -> notFound());
        if (agent.isDeleted() || !agent.isReadableBy(user.id())) {
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

    /**
     * 쓰기 전에 행 잠금을 잡고, 잠금을 잡은 뒤의 접근 범위를 다시 확인한다.
     *
     * <p>지웠는지도 잠금을 잡은 뒤에 본다. 잠금 전에 보면 그 사이 지워진 에이전트에 쓸 수 있다.
     */
    public Agent requireReadableForUpdate(CurrentUser user, String code) {
        Agent locked = agents.findByCodeForUpdate(code).orElseThrow(() -> notFound());
        if (locked.isDeleted() || !locked.isReadableBy(user.id())) throw notFound();
        return locked;
    }

    /**
     * 이미 읽은 에이전트의 행을 기다리는 쓰기 잠금으로 다시 읽는다.
     *
     * <p>{@link #requireReadableForUpdate} 와 달리 다른 요청이 잠금을 쥐고 있으면 풀릴 때까지 기다린다.
     * 스킬 저장처럼 차례로 돌아야 하는 쓰기가 쓴다. 잠금은 트랜잭션이 끝날 때 풀린다. 지웠는지와 접근
     * 범위는 잠금을 잡은 뒤에 다시 본다.
     */
    public Agent lockForUpdate(CurrentUser user, Agent agent) {
        Agent locked = agents.findByIdForUpdate(agent.id()).orElseThrow(() -> notFound());
        if (locked.isDeleted() || !locked.isReadableBy(user.id())) throw notFound();
        return locked;
    }

    /**
     * 요청자가 이 에이전트의 성격과 소개, 추천 질문을 고칠 수 있는가.
     *
     * <p>주인과 {@code ADMIN} 만 고친다. 주인은 공개 범위와 무관하게 남아, 그룹에 공개한 에이전트도 만든
     * 사람이 계속 고친다(ADR-033). 이 결정 전에 그룹 공개로 만든 에이전트는 주인이 비어 있어 {@code ADMIN}
     * 만 통과한다. 고칠 수 있는지 판정하는 곳은 모두 이 메서드를 부른다. 판정을 복사하면 한쪽만 바뀌어
     * 성격은 못 고치는데 추천 질문은 고치는 에이전트가 생긴다.
     */
    public boolean isEditableBy(CurrentUser user, Agent agent) {
        return user.isAdmin() || Objects.equals(agent.ownerUserId(), user.id());
    }

    /**
     * 번호로 에이전트를 읽는다. 지운 에이전트도 돌려준다.
     *
     * <p>지운 에이전트의 대화 이력과 사용량이 이 메서드로 이름을 읽는다. 새 turn 을 막는 판정은 부르는 쪽이
     * {@link Agent#isDeleted()} 로 한다.
     */
    public Agent requireById(Long id) {
        return agents.findById(id).orElseThrow(() -> notFound());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
    }
}
