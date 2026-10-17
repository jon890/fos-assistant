package com.bifos.assistant.context.eval;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.context.eval.MemoryEvalDataset.Case;
import com.bifos.assistant.context.eval.MemoryEvalDataset.Filler;
import com.bifos.assistant.context.eval.MemoryEvalDataset.MemorySeed;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.TestClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 사례 묶음의 사용자, 에이전트, Memory 를 데이터베이스에 넣고 지운다. 조립 측정({@link MemoryRecallEvalTest})과 답 판정
 * ({@link MemoryJudgeEvalTest})이 함께 쓴다.
 */
final class MemoryEvalFixture {

    private static final String AGENT_CODE_PREFIX = "memory-eval-";

    /** 에이전트와 collection 줄을 만든 시각이다. 측정 값에 영향이 없다. */
    private static final Instant AGENT_CREATED_AT = Instant.parse("2026-11-02T00:00:00Z");

    /** 사용자 번호와 그룹 번호가 다른 시험이나 운영 줄과 겹치지 않게 큰 수에서 시작한다. */
    private static final long USER_ID_BASE = 910_000L;

    private static final long GROUP_ID_BASE = 920_000L;

    private final MemoryEvalDataset dataset;
    private final MemoryRepository memories;
    private final AgentRepository agents;
    private final AgentMemoryCollectionRepository grants;
    private final JdbcTemplate jdbc;
    private final TestClock clock;
    private final List<Long> createdAgentIds = new ArrayList<>();


    MemoryEvalFixture(
        MemoryEvalDataset dataset,
        MemoryRepository memories,
        AgentRepository agents,
        AgentMemoryCollectionRepository grants,
        JdbcTemplate jdbc,
        TestClock clock) {
        this.dataset = dataset;
        this.memories = memories;
        this.agents = agents;
        this.grants = grants;
        this.jdbc = jdbc;
        this.clock = clock;
    }


    /** 넣은 Memory 와 연결, 만든 에이전트를 지운다. */
    void tearDown() {
        jdbc.update("DELETE FROM memory_link");
        memories.deleteAll();
        for (Long agentId : createdAgentIds) {
            jdbc.update("DELETE FROM agent_memory_collection WHERE agent_id = ?", agentId);
            agents.deleteById(agentId);
        }
        createdAgentIds.clear();
    }


    /** 사례의 Memory 와 대체 연결을 새로 넣고 사례 안의 key 마다 저장된 번호를 돌려준다. */
    Map<String, Long> seed(Case each) {
        return seed(each, true);
    }


    /**
     * 사례의 Memory 를 새로 넣는다. {@code links} 가 참이면 {@code supersedes} 마다 사람이 받아들인 대체 연결을, {@code related} 마다 효력
     * 있는 관련 연결을 함께 넣는다. 거짓이면 연결이 없는 단계 1 의 구조다(ADR-20261015 / memory-link).
     */
    Map<String, Long> seed(Case each, boolean links) {
        jdbc.update("DELETE FROM memory_link");
        memories.deleteAll();
        Instant now = clock.instant();
        if (each.filler() != null) {
            memories.saveAll(fillerOf(each.filler(), now));
        }
        Map<String, Long> ids = new HashMap<>();
        for (MemorySeed seed : each.memories()) {
            ids.put(seed.key(), memories.save(memoryOf(seed, now)).id());
        }
        if (links) {
            for (MemorySeed seed : each.memories()) {
                if (seed.supersedes() != null) {
                    jdbc.update(
                        "INSERT INTO memory_link (from_memory_id, to_memory_id, type, origin, status, created_at, updated_at)"
                            + " VALUES (?, ?, 'SUPERSEDES', 'HUMAN', 'ACTIVE', ?, ?)",
                        ids.get(seed.key()),
                        ids.get(seed.supersedes()),
                        Timestamp.from(now),
                        Timestamp.from(now));
                }
            }
            insertRelated(each, ids, now);
        }
        return ids;
    }


    /** 사례의 {@code related} 마다 번호가 작은 쪽을 {@code from} 으로 둔 효력 있는 관련 연결을 한 번씩 넣는다. */
    private void insertRelated(Case each, Map<String, Long> ids, Instant now) {
        Set<List<Long>> pairs = new HashSet<>();
        for (MemorySeed seed : each.memories()) {
            for (String key : seed.related()) {
                Long a = ids.get(seed.key());
                Long b = ids.get(key);
                pairs.add(List.of(Math.min(a, b), Math.max(a, b)));
            }
        }
        for (List<Long> pair : pairs) {
            jdbc.update(
                "INSERT INTO memory_link (from_memory_id, to_memory_id, type, origin, status, created_at, updated_at)"
                    + " VALUES (?, ?, 'RELATED', 'HUMAN', 'ACTIVE', ?, ?)",
                pair.get(0),
                pair.get(1),
                Timestamp.from(now),
                Timestamp.from(now));
        }
    }


    /** 보조 항목은 사례 항목보다 먼저 넣는다. 번호가 작은 쪽이 먼저 실리므로 사례 항목이 가장 불리한 자리에 선다. */
    private List<Memory> fillerOf(Filler filler, Instant now) {
        Instant at = now.minus(Duration.ofDays(filler.updatedDaysAgo()));
        Long owner = userOf(filler.owner()).id();
        List<Memory> rows = new ArrayList<>();
        for (int n = 1; n <= filler.count(); n++) {
            rows.add(Memory.accepted(
                MemoryScope.USER,
                owner,
                null,
                "보조 사실 " + n,
                StoredContent.plain("가".repeat(filler.contentChars())),
                MemoryPlacement.core(MemoryRetrieval.SEARCH),
                owner,
                at));
        }
        return rows;
    }


    private Memory memoryOf(MemorySeed seed, Instant now) {
        Instant at = now.minus(Duration.ofDays(seed.updatedDaysAgo()));
        StoredContent body = StoredContent.plain(seed.content());
        MemoryPlacement placement = new MemoryPlacement(seed.collection(), seed.retrieval(), seed.sensitivity());
        boolean group = seed.scope() == MemoryScope.GROUP;
        Long ownerId = group ? null : userOf(seed.owner()).id();
        Long groupId = group ? groupIdOf(seed.group()) : null;
        if (seed.entryType() == MemoryEntryType.DOCUMENT) {
            return Memory.document(
                ownerId, seed.collection(), seed.documentKey(), seed.title(), body, seed.sensitivity(), at);
        }
        return switch (seed.status()) {
            case ACCEPTED ->
                Memory.accepted(
                    seed.scope(),
                    ownerId,
                    groupId,
                    seed.title(),
                    body,
                    placement,
                    group ? firstUserIdOfGroup(seed.group()) : ownerId,
                    at);
            case PROPOSED -> Memory.proposed(ownerId, seed.title(), body, placement, null, null, at);
            case REJECTED -> {
                Memory proposed = Memory.proposed(ownerId, seed.title(), body, placement, null, null, at);
                proposed.reject(at);
                yield proposed;
            }
        };
    }


    /** 에이전트를 저장하고 사례 묶음이 적은 collection 만 받게 한다. 저장하면 딸려 오는 core 줄은 지우고 다시 넣는다. */
    Map<String, Long> createAgents() {
        Map<String, Long> agentIds = new LinkedHashMap<>();
        for (MemoryEvalDataset.Agent each : dataset.agents()) {
            String code = AGENT_CODE_PREFIX + each.key();
            Long agentId = agents.findByCode(code)
                .orElseGet(() -> agents.save(Agent.of(
                    code,
                    "회수 측정 " + each.key(),
                    code,
                    "http://runtime.test/p/" + code,
                    CostMode.API,
                    CredentialScope.DEDICATED,
                    AgentVisibility.GROUP,
                    null)))
                .id();
            createdAgentIds.add(agentId);
            jdbc.update("DELETE FROM agent_memory_collection WHERE agent_id = ?", agentId);
            each.collections()
                .forEach(grant -> grants.save(
                    AgentMemoryCollection.of(agentId, grant.collection(), grant.allowSensitive())));
            agentIds.put(each.key(), agentId);
        }
        return agentIds;
    }


    CurrentUser userOf(String key) {
        MemoryEvalDataset.User user = dataset.user(key);
        int index = dataset.users().indexOf(user);
        return new CurrentUser(USER_ID_BASE + index, key + "@example.com", key, groupIdOf(user.group()), user.role());
    }


    private Long groupIdOf(String group) {
        List<String> groups = dataset.users().stream()
            .map(MemoryEvalDataset.User::group)
            .distinct()
            .toList();
        return GROUP_ID_BASE + groups.indexOf(group);
    }


    private Long firstUserIdOfGroup(String group) {
        return dataset.users().stream()
            .filter(user -> user.group().equals(group))
            .findFirst()
            .map(user -> userOf(user.key()).id())
            .orElseThrow();
    }
}
