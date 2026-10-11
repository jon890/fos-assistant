package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.AgentMemoryCollectionChange;
import com.bifos.assistant.agent.domain.type.AgentMemoryCollectionChangeType;
import com.bifos.assistant.memory.application.MemoryCollectionService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.domain.MemoryCollectionId;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.MemoryRevision;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryChangeType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryCollectionRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** H2와 MySQL에서 실제 listener 및 주입 시계로 저장하고 commit 뒤 다시 읽는다. */
@BackendIntegrationTest
class MemoryCollectionAuditingTest {
    private static final Instant INSERT_TIME = Instant.parse("2030-01-02T03:04:05.123456789Z");
    private static final Instant CREATED = INSERT_TIME.truncatedTo(ChronoUnit.MICROS);
    private static final Instant LATER = Instant.parse("2030-02-03T04:05:06.987654321Z");
    private static final Long GROUP_ID = 710031L;

    @Autowired
    MemoryCollectionRepository collections;
    @Autowired
    MemoryCollectionService service;
    @Autowired
    EntityManager entityManager;
    @Autowired
    TestClock clock;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        collections.deleteAll();
    }

    @Test
    @DisplayName("factory는 시각이 없고 merge 반환값과 commit 뒤 ORM 및 JDBC 값은 PrePersist 시각의 마이크로초다")
    void recordsInjectedClockOnManagedMergeResult() {
        clock.set(Instant.EPOCH);
        List<MemoryCollection> originals = MemoryCollection.defaultsFor(GROUP_ID);
        assertThat(originals).extracting(MemoryCollection::createdAt).containsOnlyNulls();
        transaction().executeWithoutResult(status -> {
            clock.set(INSERT_TIME);
            List<MemoryCollection> saved = collections.saveAll(originals);
            assertThat(saved.getFirst()).isNotSameAs(originals.getFirst());
            assertThat(entityManager.contains(saved.getFirst())).isTrue();
            entityManager.flush();
            assertThat(saved).extracting(MemoryCollection::createdAt).containsOnly(CREATED);
            entityManager.clear();
            assertThat(collections.findAll()).extracting(MemoryCollection::createdAt).containsOnly(CREATED);
            // callback 뒤 commit 전 시계를 옮겨도 생성 시각은 앞서 기록한 값이다.
            clock.set(LATER);
        });
        assertPersisted(CREATED);
    }

    @Test
    @DisplayName("DB에서 읽은 detached 객체의 merge와 서비스 재조회 및 no-op flush는 생성 시각을 보존한다")
    void preservesExistingCreationTime() {
        clock.set(INSERT_TIME);
        service.collectionsOf(GROUP_ID);
        MemoryCollection detached = transaction().execute(status -> {
            MemoryCollection row = collections.findById(new MemoryCollectionId(GROUP_ID, "core")).orElseThrow();
            entityManager.detach(row);
            return row;
        });
        clock.set(LATER);
        transaction().executeWithoutResult(status -> {
            MemoryCollection merged = collections.save(detached);
            assertThat(merged).isNotSameAs(detached);
            entityManager.flush();
            assertThat(merged.createdAt()).isEqualTo(CREATED);
            entityManager.clear();
            assertThat(service.collectionsOf(GROUP_ID)).extracting(MemoryCollection::createdAt).containsOnly(CREATED);
            entityManager.flush();
        });
        assertPersisted(CREATED);
    }

    @Test
    @DisplayName("PrePersist와 flush가 끝나도 rollback한 새 그룹의 줄은 남지 않는다")
    void rollsBackAuditedRows() {
        clock.set(INSERT_TIME);
        transaction().executeWithoutResult(status -> {
            List<MemoryCollection> saved = collections.saveAll(MemoryCollection.defaultsFor(GROUP_ID));
            entityManager.flush();
            assertThat(saved).extracting(MemoryCollection::createdAt).containsOnly(CREATED);
            status.setRollbackOnly();
        });
        assertThat(collections.findByIdGroupIdOrderBySortOrderAsc(GROUP_ID)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from memory_collection where group_id = ?", Long.class, GROUP_ID)).isZero();
    }

    @Test
    @DisplayName("native SQL과 JPQL bulk는 listener를 거치지 않고 다른 업무 시각은 전달값을 유지한다")
    void preservesSqlBoundaryAndBusinessTimes() {
        clock.set(INSERT_TIME);
        service.collectionsOf(GROUP_ID);
        clock.set(LATER);
        transaction().executeWithoutResult(status -> {
            entityManager.createQuery("update MemoryCollection c set c.displayName = :name where c.id.groupId = :group")
                    .setParameter("name", "bulk")
                    .setParameter("group", GROUP_ID)
                    .executeUpdate();
            jdbc.update("update memory_collection set display_name = ? where group_id = ?", "native", GROUP_ID);
            // 업무 사건 시각은 auditing 시계와 별도로 전달한다.
            Memory memory = Memory.accepted(MemoryScope.GROUP, null, GROUP_ID, "제목", StoredContent.plain("본문"),
                    MemoryPlacement.core(MemoryRetrieval.SEARCH), 1L, CREATED);
            entityManager.persist(memory);
            MemoryRevision revision = MemoryRevision.of(memory, MemoryChangeType.UPDATED, 1L, "변경", Instant.EPOCH);
            AgentMemoryCollectionChange change = AgentMemoryCollectionChange.of(1L, "core",
                    AgentMemoryCollectionChangeType.GRANTED, false, 1L, Instant.EPOCH);
            entityManager.persist(revision);
            entityManager.persist(change);
            entityManager.flush();
            var revisionId = revision.id();
            Long changeId = change.id();
            entityManager.clear();
            assertThat(entityManager.find(MemoryRevision.class, revisionId).changedAt()).isEqualTo(Instant.EPOCH);
            assertThat(entityManager.find(AgentMemoryCollectionChange.class, changeId).changedAt()).isEqualTo(Instant.EPOCH);
            entityManager.remove(entityManager.find(MemoryRevision.class, revisionId));
            entityManager.remove(entityManager.find(AgentMemoryCollectionChange.class, changeId));
            entityManager.remove(entityManager.find(Memory.class, memory.id()));
        });
        assertPersisted(CREATED);
        assertThat(collections.findAll()).extracting(MemoryCollection::displayName).containsOnly("native");
    }

    @Test
    @DisplayName("고정 시계 reset 뒤 실제 UTC 시각이 저장되어 이전 검사 시각이 새지 않는다")
    void resetsClockBeforeNextInsert() {
        clock.set(INSERT_TIME);
        clock.reset();
        Instant before = Instant.now().minusSeconds(1);
        service.collectionsOf(GROUP_ID);
        Instant after = Instant.now().plusSeconds(1);
        assertThat(collections.findAll()).allSatisfy(row -> {
            assertThat(row.createdAt()).isBetween(before, after);
            assertThat(row.createdAt().getNano() % 1000).isZero();
        });
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactions);
    }

    private void assertPersisted(Instant expected) {
        assertThat(collections.findByIdGroupIdOrderBySortOrderAsc(GROUP_ID))
                .hasSize(7).extracting(MemoryCollection::createdAt).containsOnly(expected);
        List<Instant> timestamps = jdbc.query("select created_at from memory_collection where group_id = ?",
                (row, index) -> row.getTimestamp(1).toInstant(), GROUP_ID);
        assertThat(timestamps).hasSize(7).containsOnly(expected);
    }
}
