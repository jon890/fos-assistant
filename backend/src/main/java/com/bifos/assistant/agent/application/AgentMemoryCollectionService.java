package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentCreated;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.AgentMemoryCollectionChange;
import com.bifos.assistant.agent.domain.type.AgentMemoryCollectionChangeType;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionChangeRepository;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트가 받는 Memory collection 을 읽고, 새 에이전트에 기본 collection 을 주고, 관리자가 고른 목록으로 바꾸고 변경을 기록한다
 * (ADR-053, ADR-20261008 / agent-memory-grants-admin).
 *
 * <p>커넥터 에이전트와 찾지 못한 에이전트는 아무것도 받지 않는다. 커넥터 에이전트는 줄이 있어도 같다(ADR-045).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryCollectionService {

    private final AgentMemoryCollectionRepository grants;
    private final AgentMemoryCollectionChangeRepository changes;
    private final AgentRepository agents;
    private final Clock clock;

    /**
     * 그 에이전트의 실행이 받는 collection 이다.
     *
     * @param agentId 실행의 에이전트 번호. 없는 실행이면 null 이다
     */
    @Transactional(readOnly = true)
    public AgentMemoryGrants grantsOf(Long agentId) {
        Optional<Agent> agent = agentId == null ? Optional.empty() : agents.findById(agentId);
        if (agent.isEmpty() || agent.get().connectorManaged()) {
            return AgentMemoryGrants.none();
        }
        List<AgentMemoryCollection> rows = grants.findByIdAgentId(agentId);
        Set<String> collections =
                rows.stream().map(AgentMemoryCollection::collection).collect(Collectors.toSet());
        Set<String> sensitive = rows.stream()
                .filter(AgentMemoryCollection::allowSensitive)
                .map(AgentMemoryCollection::collection)
                .collect(Collectors.toSet());
        return new AgentMemoryGrants(collections, sensitive);
    }

    /**
     * 새로 저장한 에이전트에 기본 collection 을 준다. 커넥터 에이전트에는 주지 않는다.
     *
     * <p>저장을 부른 쪽의 트랜잭션 안에서 돈다. 에이전트 저장이 되돌려지면 이 줄도 함께 되돌려진다.
     */
    @EventListener
    @Transactional
    public void grantDefaultCollection(AgentCreated created) {
        Agent agent = created.agent();
        if (agent.connectorManaged()) {
            return;
        }
        grants.save(
                AgentMemoryCollection.of(agent.id(), AgentMemoryCollection.DEFAULT_COLLECTION, false, clock.instant()));
    }

    /**
     * 관리자가 받는 collection 을 바꿀 수 있는 에이전트를 찾는다.
     *
     * @throws ApiException 없거나 지운 에이전트면 {@code AGENT_NOT_FOUND}, 커넥터 에이전트면 {@code FORBIDDEN}
     */
    @Transactional(readOnly = true)
    public Agent requireEditableAgent(String code) {
        return requireEditable(agents.findByCode(code));
    }

    /** 그 에이전트가 지금 받는 collection 줄이다. 순서는 정하지 않는다. */
    @Transactional(readOnly = true)
    public List<AgentMemoryCollection> rowsOf(Long agentId) {
        return grants.findByIdAgentId(agentId);
    }

    /** 그 에이전트의 받는 collection 이 바뀐 최근 기록 10줄이다. 새것부터 낸다. */
    @Transactional(readOnly = true)
    public List<AgentMemoryCollectionChange> recentChangesOf(Long agentId) {
        return changes.findTop10ByAgentIdOrderByIdDesc(agentId);
    }

    /**
     * 에이전트가 받는 collection 을 관리자가 고른 목록으로 바꾸고, 바뀐 collection 마다 기록을 한 줄 남긴다.
     *
     * <p>에이전트 행을 쓰기 잠금으로 읽어 같은 에이전트의 저장을 하나씩 돌린다. <b>이 잠금 읽기가 트랜잭션의 첫 읽기여야 한다.</b>
     * 그래서 바깥 트랜잭션 안에서 부르지 않는다. 잠금 전에 읽은 줄이 영속성 문맥과 REPEATABLE READ 스냅샷에 남으면, 잠금을 기다린
     * 뒤에도 다른 저장이 커밋한 줄을 보지 못해 같은 key 를 다시 넣다 기본 키 충돌이 난다.
     *
     * <p>목록에 없는 key 의 거절은 잠금 뒤에 지금 줄을 읽고 한다. 잠금 전에 판정하면 다른 저장이 동시에 뗀 줄을 기준으로 받아들인다.
     * 바뀐 것이 없으면 아무것도 쓰지 않는다. 민감 허용만 바뀐 줄은 지우고 다시 넣지 않아 붙인 시각이 그대로 남는다.
     *
     * @param next 받을 collection 과 그 민감 허용 전체다. 비어 있으면 모두 뗀다
     * @param listedCollections 그룹의 collection 목록 key 다. 지금 받는 줄의 key 는 목록에 없어도 둘 수 있다
     * @param changedByUserId 바꾸는 관리자다
     * @throws ApiException 없거나 지운 에이전트면 {@code AGENT_NOT_FOUND}, 커넥터 에이전트면 {@code FORBIDDEN}, 목록에도 지금
     *     줄에도 없는 collection 이 있으면 {@code VALIDATION_FAILED}
     */
    @Transactional
    public void replace(Long agentId, Map<String, Boolean> next, Set<String> listedCollections, Long changedByUserId) {
        requireEditable(agents.findByIdForUpdate(agentId));
        Map<String, AgentMemoryCollection> current = grants.findByIdAgentId(agentId).stream()
                .collect(Collectors.toMap(AgentMemoryCollection::collection, Function.identity()));
        for (String key : next.keySet()) {
            if (!listedCollections.contains(key) && !current.containsKey(key)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "no such memory collection");
            }
        }

        Instant now = clock.instant();
        SortedSet<String> keys = new TreeSet<>(current.keySet());
        keys.addAll(next.keySet());
        List<String> granted = new ArrayList<>();
        List<String> revoked = new ArrayList<>();
        List<String> sensitiveChanged = new ArrayList<>();
        for (String key : keys) {
            AgentMemoryCollection row = current.get(key);
            boolean allow = Boolean.TRUE.equals(next.get(key));
            if (row == null) {
                grants.save(AgentMemoryCollection.of(agentId, key, allow, now));
                record(agentId, key, AgentMemoryCollectionChangeType.GRANTED, allow, changedByUserId, now);
                granted.add(key);
            } else if (!next.containsKey(key)) {
                grants.delete(row);
                record(
                        agentId,
                        key,
                        AgentMemoryCollectionChangeType.REVOKED,
                        row.allowSensitive(),
                        changedByUserId,
                        now);
                revoked.add(key);
            } else if (row.allowSensitive() != allow) {
                row.changeAllowSensitive(allow);
                record(agentId, key, AgentMemoryCollectionChangeType.SENSITIVE_CHANGED, allow, changedByUserId, now);
                sensitiveChanged.add(key);
            }
        }
        if (granted.isEmpty() && revoked.isEmpty() && sensitiveChanged.isEmpty()) {
            return;
        }
        log.info(
                "agent memory collections changed agentId={} by={} granted={} revoked={} sensitiveChanged={}",
                agentId,
                changedByUserId,
                granted,
                revoked,
                sensitiveChanged);
    }

    private void record(
            Long agentId,
            String collection,
            AgentMemoryCollectionChangeType type,
            boolean allowSensitive,
            Long changedByUserId,
            Instant now) {
        changes.save(AgentMemoryCollectionChange.of(agentId, collection, type, allowSensitive, changedByUserId, now));
    }

    private static Agent requireEditable(Optional<Agent> found) {
        Agent agent = found.filter(a -> !a.isDeleted())
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));
        if (agent.connectorManaged()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "connector-managed agents receive no memory");
        }
        return agent;
    }
}
