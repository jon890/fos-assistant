package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
        Agent agent = agents.findByCode(code).orElseThrow(() -> notFound());
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
        if (locked.isDeleted() || !locked.isReadableBy(user.id())) {
            throw notFound();
        }
        return locked;
    }

    /**
     * 관리자가 고칠 에이전트를 읽는다. 다른 사람의 비공개 에이전트도 읽는다. 지운 에이전트는 없는 것과 같다.
     *
     * @throws ApiException {@code FORBIDDEN}. 요청자가 {@code ADMIN} 이 아닐 때
     */
    public Agent requireForAdmin(CurrentUser user, String code) {
        requireAdmin(user);
        Agent agent = agents.findByCode(code).orElseThrow(() -> notFound());
        if (agent.isDeleted()) {
            throw notFound();
        }
        return agent;
    }

    /**
     * 관리자가 에이전트의 기본 모델과 effort 를 바꾼다. 값의 검증은 부르는 쪽이 끝낸다.
     *
     * <p>행 잠금을 잡은 뒤 지웠는지 다시 본다. 이 트랜잭션에서 그 에이전트를 처음 읽는 것이 잠금 읽기여야
     * 한다. 먼저 읽은 것이 있으면 잠금 읽기가 그 옛 값을 돌려줘, 그 사이의 삭제와 공개 범위 변경을 되돌린다.
     * 그래서 부르는 쪽의 트랜잭션에 끼지 않고 새 트랜잭션에서 돈다. 모두 null 이면 profile 의 값으로
     * 돌아간다(ADR-054).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Agent changeDefaultModel(
            CurrentUser user, String code, String provider, String model, String reasoningEffort) {
        requireAdmin(user);
        Agent locked = agents.findByCodeForUpdate(code).orElseThrow(() -> notFound());
        if (locked.isDeleted()) {
            throw notFound();
        }
        locked.changeDefaultModel(provider, model, reasoningEffort);
        return agents.save(locked);
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
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
        if (locked.isDeleted() || !locked.isReadableBy(user.id())) {
            throw notFound();
        }
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
        return !agent.connectorManaged() && (user.isAdmin() || Objects.equals(agent.ownerUserId(), user.id()));
    }

    /**
     * 번호로 에이전트를 읽는다. 지운 에이전트도 돌려준다.
     *
     * <p>지운 에이전트의 대화 이력과 사용량이 이 메서드로 이름을 읽는다. 새 turn 을 막는 판정은 부르는 쪽이
     * {@link Agent#isDeleted()} 로 한다. 행이 없거나 {@code id} 가 null 이면 {@code AGENT_NOT_FOUND} 다.
     */
    public Agent requireById(Long id) {
        return findById(id).orElseThrow(() -> notFound());
    }

    /**
     * 번호로 에이전트를 읽는다. 행이 없거나 {@code id} 가 null 이면 빈 값이다. 지운 에이전트도 돌려준다.
     *
     * <p>행이 없어도 오류로 끝내지 않고 그 칸만 비워 그릴 곳(목록, 실행 나무)이 쓴다.
     */
    public Optional<Agent> findById(Long id) {
        return id == null ? Optional.empty() : agents.findById(id);
    }

    /**
     * 번호들의 에이전트를 한 번에 읽어 번호별로 돌려준다. 행이 없는 번호는 맵에 없다.
     *
     * <p>목록이 줄마다 읽지 않게 하는 곳이다. null 은 빼고, 남은 번호가 없으면 질의하지 않는다. 부르는 쪽이
     * 에이전트 번호가 null 인 줄에도 {@code get} 을 부르므로, 수정할 수 없는 {@code Map.of()} 는 null 키로
     * 조회하면 예외를 던져 쓰지 않는다.
     */
    public Map<Long, Agent> byIds(Collection<Long> ids) {
        List<Long> present = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (present.isEmpty()) {
            return new HashMap<>();
        }
        return agents.findAllById(present).stream().collect(Collectors.toMap(Agent::id, Function.identity()));
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
    }
}
