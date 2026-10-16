package com.bifos.assistant.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.shared.domain.AuditedEntity;
import com.bifos.assistant.shared.domain.CreatedAtEntity;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.attention.domain.AttentionControlEntry;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.connector.infra.FinancialOrderLimitRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Calendar;
import java.util.TimeZone;
import java.lang.reflect.Field;
import java.util.stream.Collectors;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 공용 시계와 실제 JPA callback으로 저장 시각을 검증한다. */
@BackendIntegrationTest
@Transactional
class JpaAuditingTest {
    private static final Instant CREATED = Instant.parse("2030-01-02T03:04:05.123456789Z");
    private static final Instant LATER = Instant.parse("2030-02-03T04:05:06.987654321Z");

    @Autowired
    AppUserRepository users;
    @Autowired
    TestClock clock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager entityManager;
    @Autowired
    FinancialOrderLimitRepository limits;
    @Autowired
    PlatformTransactionManager transactions;


    @Test
    @DisplayName("사용자 생성 시각은 factory 호출이 아니라 저장 callback의 마이크로초 시각이다")
    void recordsPersistenceTimeRatherThanFactoryTime() {
        clock.set(Instant.EPOCH);
        AppUser user = AppUser.of(UUID.randomUUID() + "@audit.test", "감사 검사", 1L, UserRole.MEMBER);
        clock.set(CREATED);
        users.saveAndFlush(user);
        assertThat(jdbcInstant("select created_at from app_user where id = ?", user.id()))
            .isEqualTo(CREATED.truncatedTo(ChronoUnit.MICROS));
    }


    @Test
    @DisplayName("모든 감사 대상은 실제 factory로 저장하고 ORM과 JDBC에서 마이크로초 시각을 다시 읽는다")
    void roundTripsEveryAuditedEntity() throws IllegalAccessException {
        clock.set(CREATED);
        var fixture = JpaAuditingEntityFixtures.persistAll(entityManager);
        entityManager.flush();
        Set<Class<?>> expected = entityManager.getMetamodel().getEntities().stream().map(entity -> (Class<?>)entity.getJavaType())
            .filter(CreatedAtEntity.class::isAssignableFrom).collect(Collectors.toSet());
        assertThat(fixture.rows()).extracting(Object::getClass).containsExactlyInAnyOrderElementsOf(expected);
        for (CreatedAtEntity row : fixture.rows()) {
            Object id = entityManager.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(row);
            entityManager.detach(row);
            CreatedAtEntity loaded = entityManager.find(row.getClass(), id);
            assertThat(loaded.createdAt()).as(row.getClass().getSimpleName()).isEqualTo(micros(CREATED));
            if (loaded instanceof AuditedEntity audited) {
                assertThat(audited.updatedAt()).isEqualTo(micros(CREATED));
            }
            assertJdbcCreation(loaded);
        }
        assertThat(fixture.conversation().updatedAt()).isEqualTo(JpaAuditingEntityFixtures.BUSINESS);
        assertThat(fixture.connection().updatedAt()).isEqualTo(JpaAuditingEntityFixtures.BUSINESS);
        assertThat(fixture.binding().updatedAt()).isEqualTo(JpaAuditingEntityFixtures.BUSINESS);
        assertThat(fixture.memory().updatedAt()).isEqualTo(JpaAuditingEntityFixtures.BUSINESS);
        assertThat(fixture.execution().startedAt()).isEqualTo(JpaAuditingEntityFixtures.BUSINESS);
    }


    @Test
    @DisplayName("생성 변경 대상은 dirty update에서만 변경 시각을 옮기고 no-op과 detach merge에서는 보존한다")
    void preservesCreationAndAdvancesOnlyDirtyUpdates() {
        clock.set(CREATED);
        var fixture = JpaAuditingEntityFixtures.persistAll(entityManager);
        entityManager.flush();
        clock.set(LATER);
        for (CreatedAtEntity row : fixture.rows()) {
            if (row instanceof AppUser user) {
                user.rename("고친 이름");
            } else if (row instanceof Agent agent) {
                agent.changeDefaultModel("provider", "model", null);
            } else if (row instanceof AllowedPerson person) {
                person.disable();
            } else if (row instanceof AttentionControlEntry entry) {
                entry.rehide("changed");
            } else if (row instanceof Task task) {
                task.changeCheckEnabled(false);
            } else if (row instanceof TaskTrigger trigger) {
                trigger.moveNext(LATER.plusSeconds(10));
            } else if (row instanceof MemoryEmbedding embedding) {
                embedding.replace("different", new byte[32], new float[] {3, 4});
            }
        }
        entityManager.flush();
        List<AuditedEntity> changed = fixture.rows().stream().filter(AuditedEntity.class::isInstance).map(AuditedEntity.class::cast).toList();
        assertThat(changed).hasSize(7).allSatisfy(row -> {
            assertThat(row.createdAt()).isEqualTo(micros(CREATED));
            assertThat(row.updatedAt()).isEqualTo(micros(LATER));
        });
        clock.set(LATER.plusSeconds(60));
        entityManager.flush();
        for (CreatedAtEntity row : fixture.rows()) {
            entityManager.detach(row);
            CreatedAtEntity merged = entityManager.merge(row);
            assertThat(merged.createdAt()).isEqualTo(micros(CREATED));
        }
        entityManager.flush();
        entityManager.clear();
        assertThat(changed).allSatisfy(row -> {
            Object id = entityManager.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(row);
            AuditedEntity loaded = entityManager.find(row.getClass(), id);
            assertThat(loaded.createdAt()).isEqualTo(micros(CREATED));
            assertThat(loaded.updatedAt()).isEqualTo(micros(LATER));
        });
    }


    @Test
    @DisplayName("새 모델 기본값과 금융 상한 bulk는 감사 시각을 직접 채우고 같은 기본값은 변경하지 않는다")
    void recordsBulkChangesAndPreservesNoOp() {
        clock.set(CREATED);
        AppUser user = users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@audit.test", "감사", 1L, UserRole.MEMBER));
        assertThat(users.updateModelDefaultTier(user.id(), "FAST", micros(LATER))).isEqualTo(1);
        assertThat(users.findById(user.id()).orElseThrow().updatedAt()).isEqualTo(micros(LATER));
        assertThat(users.updateModelDefaultTier(user.id(), "FAST", micros(LATER.plusSeconds(60)))).isZero();
        assertThat(users.findById(user.id()).orElseThrow().updatedAt()).isEqualTo(micros(LATER));
        assertThat(users.updateModelDefaultTier(user.id(), null, micros(LATER.plusSeconds(120)))).isEqualTo(1);
        Instant limitTime = micros(LATER.plusSeconds(180));
        limits.store(user.id(), null, null, null, null, null, JpaAuditingEntityFixtures.BUSINESS, limitTime);
        entityManager.clear();
        assertThat(users.findById(user.id()).orElseThrow().updatedAt()).isEqualTo(limitTime);
        assertThat(jdbcInstant("select limit_updated_at from app_user where id = ?", user.id()))
            .isEqualTo(JpaAuditingEntityFixtures.BUSINESS);
    }


    @Test
    @DisplayName("과거 NULL 변경 시각은 첫 변경에 채우고 로그인 bulk는 관리 변경 시각을 보존한다")
    void preservesUnknownHistoryAndLoginBoundary() {
        clock.set(CREATED);
        AppUser user = users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@audit.test", "과거", 1L, UserRole.MEMBER));
        jdbc.update("update app_user set updated_at = null where id = ?", user.id());
        entityManager.clear();
        AppUser historic = users.findById(user.id()).orElseThrow();
        assertThat(historic.updatedAt()).isNull();
        clock.set(LATER);
        historic.rename("변경");
        entityManager.flush();
        assertThat(historic.updatedAt()).isEqualTo(micros(LATER));
        AllowedPerson person = AllowedPerson.of(UUID.randomUUID() + "@audit.test", "허용", UUID.randomUUID().toString());
        entityManager.persist(person);
        entityManager.flush();
        Instant before = person.updatedAt();
        clock.set(LATER.plusSeconds(60));
        entityManager.createQuery("update AllowedPerson p set p.lastLoginAt = :at where p.id = :id")
            .setParameter("at", micros(LATER.plusSeconds(60))).setParameter("id", person.id()).executeUpdate();
        entityManager.clear();
        assertThat(entityManager.find(AllowedPerson.class, person.id()).updatedAt()).isEqualTo(before);
    }


    @Test
    @DisplayName("복합 ID의 신규 merge 반환값과 기존 detached merge 및 권한 재부여의 생성 시각을 구분한다")
    void preservesCompositeCreationAcrossMergeAndRegrant() {
        clock.set(CREATED);
        var fixture = JpaAuditingEntityFixtures.persistAll(entityManager);
        AgentMemoryCollection original = AgentMemoryCollection.of(fixture.agent().id(), "career", false);
        assertThat(original.createdAt()).isNull();
        AgentMemoryCollection saved = entityManager.merge(original);
        entityManager.flush();
        assertThat(saved).isNotSameAs(original);
        assertThat(saved.createdAt()).isEqualTo(micros(CREATED));
        assertThat(original.createdAt()).isNull();
        clock.set(LATER);
        saved.changeAllowSensitive(true);
        entityManager.flush();
        entityManager.detach(saved);
        AgentMemoryCollection merged = entityManager.merge(saved);
        entityManager.flush();
        assertThat(merged.createdAt()).isEqualTo(micros(CREATED));
        entityManager.remove(merged);
        entityManager.flush();
        entityManager.clear();
        AgentMemoryCollection granted = entityManager.merge(AgentMemoryCollection.of(fixture.agent().id(), "career", false));
        entityManager.flush();
        assertThat(granted.createdAt()).isEqualTo(micros(LATER));
    }


    @Test
    @DisplayName("문맥 참조는 지정 복합 ID를 새로 저장하고 같은 position을 두 번 넣으면 거절한다")
    void rejectsDuplicateContextPosition() {
        clock.set(CREATED);
        var fixture = JpaAuditingEntityFixtures.persistAll(entityManager);
        entityManager.flush();
        entityManager.clear();
        assertThatThrownBy(() -> {
            entityManager.persist(ExecutionContextSource.of(fixture.execution().id(), 0, "MEMORY", "memory:duplicate", "FULL", "FRESH"));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }


    @Test
    @DisplayName("지정 memoryId의 새 벡터 merge는 반환값에 시각을 채우고 기존 벡터 교체는 생성 시각을 보존한다")
    void preservesAssignedEmbeddingCreationOnMerge() {
        clock.set(CREATED);
        var fixture = JpaAuditingEntityFixtures.persistAll(entityManager);
        Memory memory = Memory.accepted(MemoryScope.USER, fixture.user().id(), null, "벡터", StoredContent.plain("본문"),
            MemoryPlacement.core(MemoryRetrieval.SEARCH), fixture.user().id(), JpaAuditingEntityFixtures.BUSINESS);
        entityManager.persist(memory);
        MemoryEmbedding original = MemoryEmbedding.of(memory.id(), "first", new byte[32], new float[] {1, 2});
        MemoryEmbedding managed = entityManager.merge(original);
        entityManager.flush();
        assertThat(managed).isNotSameAs(original);
        assertThat(original.createdAt()).isNull();
        assertThat(managed.createdAt()).isEqualTo(micros(CREATED));
        entityManager.detach(managed);
        clock.set(LATER);
        managed.replace("second", new byte[32], new float[] {3, 4});
        MemoryEmbedding replaced = entityManager.merge(managed);
        entityManager.flush();
        entityManager.clear();
        MemoryEmbedding loaded = entityManager.find(MemoryEmbedding.class, replaced.memoryId());
        assertThat(loaded.createdAt()).isEqualTo(micros(CREATED));
        assertThat(loaded.updatedAt()).isEqualTo(micros(LATER));
    }


    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("감사 시각이 채워진 사용자 생성도 rollback하면 행이 남지 않는다")
    void rollsBackAuditedCreation() {
        String email = UUID.randomUUID() + "@audit.test";
        clock.set(CREATED);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            AppUser saved = users.saveAndFlush(AppUser.of(email, "되돌림", 1L, UserRole.MEMBER));
            assertThat(saved.createdAt()).isEqualTo(micros(CREATED));
            assertThat(saved.updatedAt()).isEqualTo(micros(CREATED));
            status.setRollbackOnly();
        });
        assertThat(users.findByEmail(email)).isEmpty();
    }


    private void assertJdbcCreation(CreatedAtEntity row) throws IllegalAccessException {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Field field : row.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(Id.class) && !field.isAnnotationPresent(EmbeddedId.class)) {
                continue;
            }
            field.setAccessible(true);
            if (field.isAnnotationPresent(EmbeddedId.class)) {
                Object embedded = field.get(row);
                for (Field component : embedded.getClass().getDeclaredFields()) {
                    if (!component.isAnnotationPresent(Column.class)) {
                        continue;
                    }
                    component.setAccessible(true);
                    where.add(component.getAnnotation(Column.class).name() + " = ?");
                    args.add(component.get(embedded));
                }
            } else {
                Column column = field.getAnnotation(Column.class);
                where.add((column == null || column.name().isEmpty() ? field.getName() : column.name()) + " = ?");
                args.add(field.get(row));
            }
        }
        assertThat(where).isNotEmpty();
        String table = row.getClass().getAnnotation(Table.class).name();
        List<Instant> result = jdbc.query("select created_at from " + table + " where " + String.join(" and ", where),
            (record, index) -> record.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant(), args.toArray());
        assertThat(result).containsExactly(micros(CREATED));
    }


    private static Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }


    private Instant jdbcInstant(String sql, Object... args) {
        return jdbc.queryForObject(sql,
            (record, index) -> record.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant(), args);
    }
}
