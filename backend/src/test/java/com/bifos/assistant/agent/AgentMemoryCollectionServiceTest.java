package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.application.AgentMemoryCollectionService;
import com.bifos.assistant.agent.application.AgentMemoryGrants;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.AgentMemoryCollectionChange;
import com.bifos.assistant.agent.domain.type.AgentMemoryCollectionChangeType;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 에이전트가 받는 Memory collection 과 새 에이전트의 기본 부여, 관리자의 변경과 그 기록을 확인한다
 * (ADR-053, ADR-20261008 / agent-memory-grants-admin).
 */
@BackendIntegrationTest
class AgentMemoryCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    /** 그룹의 collection 목록 key 다. */
    private static final Set<String> LISTED = Set.of("core", "career", "health");

    private static final Long ADMIN_ID = 7L;

    @Autowired
    AgentMemoryCollectionService service;

    @Autowired
    AgentMemoryCollectionRepository grants;

    @Autowired
    AgentRepository agents;

    @Autowired
    TestClock clock;

    @Test
    @DisplayName("새로 저장한 에이전트는 core 를 민감 항목 없이 받고 다시 저장해도 줄이 늘지 않는다")
    void newAgentsReceiveCoreOnceWithoutSensitiveItems() {
        Agent agent = agents.save(agent());

        AgentMemoryGrants granted = service.grantsOf(agent.id());
        assertThat(granted.collections()).containsExactly("core");
        assertThat(granted.sensitiveCollections()).isEmpty();

        agent.assignFlow(null);
        agents.save(agent);
        agents.saveAndFlush(agents.findById(agent.id()).orElseThrow());
        assertThat(grants.findByIdAgentId(agent.id())).hasSize(1);
    }

    @Test
    @DisplayName("이미 저장한 에이전트를 다시 저장해도 관리자가 바꾼 허용을 되돌리지 않는다")
    void savingAnExistingAgentAgainDoesNotResetItsGrants() {
        Agent agent = agents.save(agent());
        // 관리자가 core 의 민감 허용을 켰다. 사건이 다시 나가면 이 줄이 거짓으로 덮인다.
        grants.save(AgentMemoryCollection.of(agent.id(), "core", true, NOW));
        agent.assignFlow(null);
        agents.save(agent);
        agents.saveAndFlush(agents.findById(agent.id()).orElseThrow());
        assertThat(service.grantsOf(agent.id()).sensitiveCollections()).containsExactly("core");

        // 관리자가 core 를 뺐다. 사건이 다시 나가면 이 줄이 되살아난다.
        grants.deleteAll(grants.findByIdAgentId(agent.id()));
        agents.save(agents.findById(agent.id()).orElseThrow());
        assertThat(grants.findByIdAgentId(agent.id())).isEmpty();
    }

    @Test
    @DisplayName("saveAndFlush 로 저장한 에이전트도 core 를 받는다")
    void agentsSavedWithFlushAlsoReceiveCore() {
        Agent agent = agents.saveAndFlush(agent());

        assertThat(service.grantsOf(agent.id()).collections()).containsExactly("core");
    }

    @Test
    @DisplayName("관리자가 넓힌 collection 과 민감 허용을 그대로 낸다")
    void returnsWidenedCollectionsAndSensitiveAllowance() {
        Agent agent = agents.save(agent());
        grants.save(AgentMemoryCollection.of(agent.id(), "career", false, NOW));
        grants.save(AgentMemoryCollection.of(agent.id(), "identity", true, NOW));

        AgentMemoryGrants granted = service.grantsOf(agent.id());

        assertThat(granted.collections()).containsExactlyInAnyOrder("core", "career", "identity");
        assertThat(granted.sensitiveCollections()).containsExactly("identity");
    }

    @Test
    @DisplayName("커넥터 에이전트는 저장할 때 아무것도 받지 않고 줄이 있어도 받지 않는다")
    void connectorAgentsReceiveNothingEvenWithRows() {
        Agent connector = agent();
        connector.markConnectorManaged();
        Agent saved = agents.save(connector);

        assertThat(grants.findByIdAgentId(saved.id())).isEmpty();
        assertThat(service.grantsOf(saved.id()).collections()).isEmpty();

        grants.save(AgentMemoryCollection.of(saved.id(), "core", true, NOW));
        AgentMemoryGrants granted = service.grantsOf(saved.id());
        assertThat(granted.collections()).isEmpty();
        assertThat(granted.sensitiveCollections()).isEmpty();
    }

    @Test
    @DisplayName("없는 에이전트와 에이전트가 없는 실행은 아무것도 받지 않는다")
    void unknownAgentsReceiveNothing() {
        assertThat(service.grantsOf(9_999_999L).collections()).isEmpty();
        assertThat(service.grantsOf(null).collections()).isEmpty();
    }

    @Test
    @DisplayName("줄을 모두 뺀 에이전트는 core 도 받지 않는다")
    void agentsWithEveryRowRemovedReceiveNothing() {
        Agent agent = agents.save(agent());
        grants.deleteAll(grants.findByIdAgentId(agent.id()));

        assertThat(service.grantsOf(agent.id()).collections()).isEmpty();
    }

    @Test
    @DisplayName("관리자가 고른 목록으로 바꾸면 붙이고 떼고 민감 허용을 바꾸고 한 번에 기록한다")
    void replacesCollectionsAndRecordsEachChange() {
        Agent agent = agents.save(agent());
        Instant firstSave = Instant.parse("2026-10-08T01:00:00Z");
        clock.set(firstSave);

        service.replace(agent.id(), Map.of("career", true), LISTED, ADMIN_ID);

        AgentMemoryGrants granted = service.grantsOf(agent.id());
        assertThat(granted.collections()).containsExactly("career");
        assertThat(granted.sensitiveCollections()).containsExactly("career");
        // 새것부터 낸다. 한 저장의 기록은 collection key 순서로 들어가므로 core 가 career 보다 뒤에 들어갔다.
        assertThat(service.recentChangesOf(agent.id()))
                .extracting(
                        AgentMemoryCollectionChange::collection,
                        AgentMemoryCollectionChange::changeType,
                        AgentMemoryCollectionChange::allowSensitive,
                        AgentMemoryCollectionChange::changedByUserId,
                        AgentMemoryCollectionChange::changedAt)
                .containsExactly(
                        tuple("core", AgentMemoryCollectionChangeType.REVOKED, false, ADMIN_ID, firstSave),
                        tuple("career", AgentMemoryCollectionChangeType.GRANTED, true, ADMIN_ID, firstSave));
        assertThat(service.rowsOf(agent.id()))
                .extracting(AgentMemoryCollection::createdAt)
                .containsExactly(firstSave);

        clock.advance(Duration.ofHours(1));
        service.replace(agent.id(), Map.of("career", false), LISTED, ADMIN_ID);

        assertThat(service.grantsOf(agent.id()).sensitiveCollections()).isEmpty();
        List<AgentMemoryCollectionChange> changes = service.recentChangesOf(agent.id());
        assertThat(changes).hasSize(3);
        assertThat(changes.getFirst())
                .extracting(
                        AgentMemoryCollectionChange::collection,
                        AgentMemoryCollectionChange::changeType,
                        AgentMemoryCollectionChange::allowSensitive,
                        AgentMemoryCollectionChange::changedAt)
                .containsExactly(
                        "career",
                        AgentMemoryCollectionChangeType.SENSITIVE_CHANGED,
                        false,
                        firstSave.plus(Duration.ofHours(1)));
        assertThat(service.rowsOf(agent.id()))
                .as("민감 허용만 바꾼 줄은 붙인 시각을 그대로 둔다")
                .extracting(AgentMemoryCollection::collection, AgentMemoryCollection::createdAt)
                .containsExactly(tuple("career", firstSave));
    }

    @Test
    @DisplayName("바뀐 것이 없으면 기록하지 않는다")
    void recordsNothingWhenNothingChanges() {
        Agent agent = agents.save(agent());

        service.replace(agent.id(), Map.of("core", false), LISTED, ADMIN_ID);

        assertThat(service.recentChangesOf(agent.id())).isEmpty();
        assertThat(service.rowsOf(agent.id()))
                .extracting(AgentMemoryCollection::collection, AgentMemoryCollection::allowSensitive)
                .containsExactly(tuple("core", false));
    }

    @Test
    @DisplayName("빈 목록이면 모두 떼어 그 에이전트는 아무것도 받지 않는다")
    void emptyListRevokesEverything() {
        Agent agent = agents.save(agent());

        service.replace(agent.id(), Map.of(), LISTED, ADMIN_ID);

        assertThat(service.grantsOf(agent.id()).collections()).isEmpty();
        assertThat(service.recentChangesOf(agent.id()))
                .extracting(AgentMemoryCollectionChange::collection, AgentMemoryCollectionChange::changeType)
                .containsExactly(tuple("core", AgentMemoryCollectionChangeType.REVOKED));
    }

    @Test
    @DisplayName("그룹 목록에도 지금 줄에도 없는 collection 은 거절한다")
    void rejectsCollectionsNeitherListedNorHeld() {
        Agent agent = agents.save(agent());

        assertThatThrownBy(() -> service.replace(agent.id(), Map.of("nope", false), LISTED, ADMIN_ID))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(service.rowsOf(agent.id()))
                .extracting(AgentMemoryCollection::collection, AgentMemoryCollection::allowSensitive)
                .containsExactly(tuple("core", false));
        assertThat(service.recentChangesOf(agent.id())).isEmpty();

        // 목록에서 빠졌지만 지금 받는 줄은 그대로 두고 민감 허용도 바꿀 수 있다.
        grants.save(AgentMemoryCollection.of(agent.id(), "identity", false, NOW));
        service.replace(agent.id(), Map.of("core", false, "identity", true), LISTED, ADMIN_ID);

        assertThat(service.grantsOf(agent.id()).collections()).containsExactlyInAnyOrder("core", "identity");
        assertThat(service.grantsOf(agent.id()).sensitiveCollections()).containsExactly("identity");
    }

    @Test
    @DisplayName("두 관리자가 동시에 저장하면 하나씩 돌고 기록이 둘 다 남는다")
    void concurrentSavesRunOneAtATimeAndBothAreRecorded() throws Exception {
        // H2 는 MySQL 의 스냅샷 문제를 재현하지 못한다. 이 시험은 잠금 경로가 빠지지 않았음만 지킨다.
        Agent agent = agents.save(agent());
        Long first = 7L;
        Long second = 8L;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> grantCareer = pool.submit(() -> {
                start.await();
                service.replace(agent.id(), Map.of("career", true), LISTED, first);
                return null;
            });
            Future<?> keepBoth = pool.submit(() -> {
                start.await();
                service.replace(agent.id(), Map.of("career", false, "core", false), LISTED, second);
                return null;
            });
            start.countDown();
            grantCareer.get(30, TimeUnit.SECONDS);
            keepBoth.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<AgentMemoryCollectionChange> changes = service.recentChangesOf(agent.id()).stream()
                .sorted(Comparator.comparing(AgentMemoryCollectionChange::id))
                .toList();
        Long later = changes.getLast().changedByUserId();
        int firstSaverRows = (int) changes.stream()
                .takeWhile(c -> !c.changedByUserId().equals(later))
                .count();
        assertThat(changes.subList(firstSaverRows, changes.size()))
                .as("나중 저장의 기록은 앞 저장의 기록 뒤에 모여 있다: %s", changes)
                .allMatch(c -> c.changedByUserId().equals(later));

        if (later.equals(second)) {
            // core 만 받던 상태에서 career 를 붙이고 core 를 뗀 뒤, career 의 민감 허용을 끄고 core 를 다시 붙였다.
            assertThat(changes)
                    .extracting(
                            AgentMemoryCollectionChange::changedByUserId,
                            AgentMemoryCollectionChange::collection,
                            AgentMemoryCollectionChange::changeType,
                            AgentMemoryCollectionChange::allowSensitive)
                    .containsExactly(
                            tuple(first, "career", AgentMemoryCollectionChangeType.GRANTED, true),
                            tuple(first, "core", AgentMemoryCollectionChangeType.REVOKED, false),
                            tuple(second, "career", AgentMemoryCollectionChangeType.SENSITIVE_CHANGED, false),
                            tuple(second, "core", AgentMemoryCollectionChangeType.GRANTED, false));
            assertThat(service.rowsOf(agent.id()))
                    .extracting(AgentMemoryCollection::collection, AgentMemoryCollection::allowSensitive)
                    .containsExactlyInAnyOrder(tuple("career", false), tuple("core", false));
        } else {
            // core 만 받던 상태에서 career 를 민감 허용 없이 붙인 뒤, career 의 민감 허용을 켜고 core 를 뗐다.
            assertThat(changes)
                    .extracting(
                            AgentMemoryCollectionChange::changedByUserId,
                            AgentMemoryCollectionChange::collection,
                            AgentMemoryCollectionChange::changeType,
                            AgentMemoryCollectionChange::allowSensitive)
                    .containsExactly(
                            tuple(second, "career", AgentMemoryCollectionChangeType.GRANTED, false),
                            tuple(first, "career", AgentMemoryCollectionChangeType.SENSITIVE_CHANGED, true),
                            tuple(first, "core", AgentMemoryCollectionChangeType.REVOKED, false));
            assertThat(service.rowsOf(agent.id()))
                    .extracting(AgentMemoryCollection::collection, AgentMemoryCollection::allowSensitive)
                    .containsExactly(tuple("career", true));
        }
    }

    @Test
    @DisplayName("옛 커넥터 에이전트와 지운 에이전트는 바꾸지 못한다")
    void rejectsConnectorAndDeletedAgents() {
        Agent connector = agent();
        connector.markConnectorManaged();
        Agent savedConnector = agents.save(connector);
        Agent deleted = agent();
        deleted.markDeleted(NOW);
        Agent savedDeleted = agents.save(deleted);

        assertThatThrownBy(() -> service.replace(savedConnector.id(), Map.of("core", false), LISTED, ADMIN_ID))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.requireEditableAgent(savedConnector.code()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.replace(savedDeleted.id(), Map.of("core", false), LISTED, ADMIN_ID))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        assertThatThrownBy(() -> service.requireEditableAgent(savedDeleted.code()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        assertThat(grants.findByIdAgentId(savedConnector.id())).isEmpty();
        assertThat(service.recentChangesOf(savedConnector.id())).isEmpty();
        assertThat(service.recentChangesOf(savedDeleted.id())).isEmpty();
    }

    private static Agent agent() {
        String code = "memory-grant-" + UUID.randomUUID().toString().substring(0, 12);
        return Agent.of(
                code,
                "collection 검사",
                code,
                "http://runtime.test/p/" + code,
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.GROUP,
                null,
                Instant.now());
    }
}
