package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import db.migration.V20261016224030__TaskTriggerIntoTask;
import db.migration.V20261016224032__TaskScheduleConstraints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 실제 옛 스키마의 일정과 발화 기록을 새 구조로 옮겨 본다. */
@Tag("mysql")
class TaskScheduleMigrationTest {

    private static final Instant SCHEDULED = Instant.parse("2026-11-01T00:00:00.123456Z");

    @TempDir
    Path directory;

    private Flyway flyway;


    @Test
    @DisplayName("일정의 모든 값과 발화 기록을 보존하고 task 기준 중복 방지와 외래 키를 유지한다")
    void migratesSchedulesAndKeepsRunConstraints() throws IOException {
        JdbcTemplate jdbc = oldSchema();
        task(jdbc, 10);
        task(jdbc, 20);
        trigger(jdbc, 101, 10);
        trigger(jdbc, 102, 20);
        assertThatThrownBy(() -> trigger(jdbc, 103, 10))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE task_trigger SET type = 'ONCE', cron_expr = NULL, fire_at = ?, next_fire_at = NULL WHERE id = 102", Timestamp.from(SCHEDULED));
        run(jdbc, 201, 10, 101);
        run(jdbc, 202, 20, 102);

        upgrade();

        assertThat(jdbc.queryForMap("""
                SELECT trigger_type, cron_expr, fire_at, time_zone, missed_policy FROM task WHERE id = 10
                """))
            .containsEntry("trigger_type", "CRON").containsEntry("cron_expr", "0 9 * * *")
            .containsEntry("time_zone", "Asia/Seoul").containsEntry("missed_policy", "RUN_ONCE")
            .containsEntry("fire_at", null);
        assertThat(jdbc.queryForObject("SELECT next_fire_at FROM task WHERE id = 10", Timestamp.class).toInstant())
            .isEqualTo(SCHEDULED);
        assertThat(jdbc.queryForObject("SELECT last_fired_at FROM task WHERE id = 10", Timestamp.class).toInstant())
            .isEqualTo(SCHEDULED.minusSeconds(60));
        assertThat(jdbc.queryForMap("SELECT trigger_type, cron_expr, next_fire_at FROM task WHERE id = 20"))
            .containsEntry("trigger_type", "ONCE").containsEntry("cron_expr", null).containsEntry("next_fire_at", null);
        assertThat(jdbc.queryForObject("SELECT fire_at FROM task WHERE id = 20", Timestamp.class).toInstant())
            .isEqualTo(SCHEDULED);
        assertThat(jdbc.queryForList("SELECT task_id FROM task_run ORDER BY id", Long.class)).containsExactly(10L, 20L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'task_trigger'
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'task_run' AND column_name = 'trigger_id'
                """, Long.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_run (public_id, task_id, owner_user_id, scheduled_for, status, created_at)
                SELECT UNHEX(REPLACE(UUID(), '-', '')), task_id, owner_user_id, scheduled_for, status, created_at
                FROM task_run WHERE id = 201
                """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE task_run SET task_id = 999 WHERE id = 201"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'task' AND index_name = 'idx_task_due'
                ORDER BY seq_in_index
                """, String.class)).containsExactly("state", "next_fire_at", "id");
    }


    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "mismatched"})
    @DisplayName("일정 누락과 중복, 다른 작업의 일정 참조는 컬럼을 추가하기 전에 거절한다")
    void rejectsInvalidOldData(String violation) throws IOException {
        JdbcTemplate jdbc = oldSchema();
        task(jdbc, 10);
        if (!"missing".equals(violation)) {
            trigger(jdbc, 101, 10);
        }
        if ("duplicate".equals(violation)) {
            jdbc.execute("ALTER TABLE task_trigger ADD INDEX idx_test_task (task_id), DROP INDEX uk_task_trigger_task");
            trigger(jdbc, 102, 10);
        } else if ("mismatched".equals(violation)) {
            task(jdbc, 20);
            trigger(jdbc, 102, 20);
            run(jdbc, 201, 20, 101);
        }

        assertThatThrownBy(this::upgrade).hasStackTraceContaining("requires one matching schedule per task");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'task' AND column_name = 'trigger_type'
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task", Long.class)).isPositive();
    }


    private JdbcTemplate oldSchema() throws IOException {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        Files.writeString(directory.resolve("V1__parents.sql"), """
                CREATE TABLE app_user (id BIGINT PRIMARY KEY);
                INSERT INTO app_user VALUES (1);
                CREATE TABLE conversation (id BIGINT PRIMARY KEY);
                """);
        Path migrations = Path.of("src/main/resources/db/migration");
        Files.copy(migrations.resolve("V68__task.sql"), directory.resolve("V68__task.sql"));
        flyway = Flyway.configure().dataSource(database.url(), database.username(), database.password())
            .locations("filesystem:" + directory)
            .javaMigrations(new V20261016224030__TaskTriggerIntoTask(), new V20261016224032__TaskScheduleConstraints())
            .target("68").load();
        flyway.migrate();
        return new JdbcTemplate(new DriverManagerDataSource(database.url(), database.username(), database.password()));
    }


    private void upgrade() throws IOException {
        Path migrations = Path.of("src/main/resources/db/migration");
        try (var files = Files.list(migrations)) {
            for (Path migration : files.filter(file -> List.of(
                "task_schedule_backfill.sql")
                .stream().anyMatch(suffix -> file.getFileName().toString().endsWith("__" + suffix))).toList()) {
                Files.copy(migration, directory.resolve(migration.getFileName()));
            }
        }
        Flyway.configure().configuration(flyway.getConfiguration()).target("latest").load().migrate();
    }


    private static void task(JdbcTemplate jdbc, long id) {
        jdbc.update("""
                INSERT INTO task (id, public_id, owner_user_id, agent_id, title, instruction, state,
                  conversation_mode, notify, created_at, updated_at)
                VALUES (?, UNHEX(REPLACE(UUID(), '-', '')), 1, 1, '예약', '실행', 'ACTIVE', 'SINGLE', 'NEVER', ?, ?)
                """, id, Timestamp.from(SCHEDULED), Timestamp.from(SCHEDULED));
    }


    private static void trigger(JdbcTemplate jdbc, long id, long taskId) {
        jdbc.update("""
                INSERT INTO task_trigger (id, task_id, type, cron_expr, fire_at, time_zone, missed_policy,
                  next_fire_at, last_fired_at, created_at, updated_at)
                VALUES (?, ?, 'CRON', '0 9 * * *', NULL, 'Asia/Seoul', 'RUN_ONCE', ?, ?, ?, ?)
                """, id, taskId, Timestamp.from(SCHEDULED),
            Timestamp.from(SCHEDULED.minusSeconds(60)), Timestamp.from(SCHEDULED), Timestamp.from(SCHEDULED));
    }


    private static void run(JdbcTemplate jdbc, long id, long taskId, long triggerId) {
        jdbc.update("""
                INSERT INTO task_run (id, public_id, task_id, trigger_id, owner_user_id, scheduled_for, status, created_at)
                VALUES (?, UNHEX(REPLACE(UUID(), '-', '')), ?, ?, 1, ?, 'QUEUED', ?)
                """, id, taskId, triggerId, Timestamp.from(SCHEDULED), Timestamp.from(SCHEDULED));
    }
}
