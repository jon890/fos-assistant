package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재조회 작업 줄을 사용량 원장으로 넓히는 마이그레이션이 칸을 더하고 지난 자식을 다시 조회 대기로 넣는지 본다(ADR-062).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다.
 */
class SubagentUsageLedgerMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.valueOf("2026-09-01 00:00:00");
    private static final Timestamp OLD_NEXT_ATTEMPT_AT = Timestamp.valueOf("2026-09-01 00:05:00");
    private static final Timestamp OLD_EXPIRES_AT = Timestamp.valueOf("2026-09-02 00:00:00");

    private Database database;

    @BeforeEach
    void setUp() throws SQLException {
        database = createDatabase();
        migrate("54");
        seed();
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
                "jdbc:h2:mem:subagent-usage-ledger-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    @DisplayName("사용량과 금액을 적는 칸이 모두 비어 있어도 되는 칸으로 생긴다")
    void addsNullableLedgerColumns() throws SQLException {
        try (Connection connection = connect()) {
            for (String name : List.of(
                    "PROVIDER",
                    "MODEL",
                    "INPUT_TOKENS",
                    "CACHE_READ_TOKENS",
                    "CACHE_WRITE_TOKENS",
                    "OUTPUT_TOKENS",
                    "ESTIMATED_COST_MICROS",
                    "ACTUAL_COST_MICROS",
                    "COST_CURRENCY",
                    "PRICING_VERSION",
                    "RECORDED_AT")) {
                try (ResultSet column = column(connection, name)) {
                    assertThat(column.next())
                            .as("subagent_usage_job.%s 칸", name)
                            .isTrue();
                    assertThat(column.getString("IS_NULLABLE"))
                            .as("subagent_usage_job.%s 의 NULL 허용", name)
                            .isEqualTo("YES");
                }
            }
            assertThat(columnSize(connection, "PROVIDER")).isEqualTo(64);
            assertThat(columnSize(connection, "MODEL")).isEqualTo(128);
            assertThat(columnSize(connection, "COST_CURRENCY")).isEqualTo(3);
            assertThat(columnSize(connection, "PRICING_VERSION")).isEqualTo(32);
        }
    }

    @Test
    @DisplayName("DONE 이던 줄은 사용량 칸이 빈 채 처음 시각부터 다시 조회 대기가 된다")
    void requeuesDoneJobs() throws SQLException {
        Job job = job(801, "done-child");

        assertThat(job.status()).isEqualTo("WAITING");
        assertThat(job.unconfirmedReason()).isNull();
        assertThat(job.createdAt()).isEqualTo(CREATED_AT);
        assertThat(job.nextAttemptAt()).as("next_attempt_at 은 created_at 과 같다").isEqualTo(CREATED_AT);
        assertThat(job.expiresAt()).as("기한을 다시 준다").isAfter(OLD_EXPIRES_AT);
        assertThat(job.attempts()).isZero();
        assertThat(job.backoffAttempts()).isZero();
        assertThat(job.recordedAt()).isNull();
        assertThat(jobCount(801, "done-child")).as("이미 줄이 있는 자식은 다시 넣지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("WAITING 과 EXPIRED 이던 줄은 그대로 둔다")
    void keepsWaitingAndExpiredJobs() throws SQLException {
        Job waiting = job(801, "waiting-child");
        assertThat(waiting.status()).isEqualTo("WAITING");
        assertThat(waiting.nextAttemptAt()).isEqualTo(OLD_NEXT_ATTEMPT_AT);
        assertThat(waiting.expiresAt()).isEqualTo(OLD_EXPIRES_AT);
        assertThat(waiting.attempts()).isEqualTo(3);
        assertThat(waiting.backoffAttempts()).isEqualTo(2);

        Job expired = job(801, "expired-child");
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.unconfirmedReason()).isEqualTo("DEADLINE");
        assertThat(expired.nextAttemptAt()).isEqualTo(OLD_NEXT_ATTEMPT_AT);
        assertThat(expired.expiresAt()).isEqualTo(OLD_EXPIRES_AT);
        assertThat(expired.attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("작업 줄이 없던 자식은 시작 사건이 중복돼도 WAITING 한 줄로 들어온다")
    void insertsOneWaitingJobForChildWithoutJob() throws SQLException {
        assertThat(jobCount(801, "new-child")).isEqualTo(1);
        Job job = job(801, "new-child");

        assertThat(job.status()).isEqualTo("WAITING");
        assertThat(job.unconfirmedReason()).isNull();
        assertThat(job.parentSessionId()).isEqualTo("parent-801");
        assertThat(job.profileName()).isEqualTo("dad");
        assertThat(job.apiBaseUrl()).isEqualTo("http://runtime.test/p/dad");
        assertThat(job.createdAt()).as("가장 이른 시작 사건의 시각").isEqualTo(Timestamp.valueOf("2026-09-01 00:00:10"));
        assertThat(job.nextAttemptAt()).isEqualTo(job.createdAt());
        assertThat(job.expiresAt()).isAfter(OLD_EXPIRES_AT);
        assertThat(job.attempts()).isZero();
        assertThat(job.backoffAttempts()).isZero();
    }

    @Test
    @DisplayName("에이전트가 지워졌거나 없는 부모의 자식은 EXPIRED 와 AGENT_MISSING 으로 들어온다")
    void expiresChildOfMissingAgent() throws SQLException {
        Job deleted = job(802, "gone-child");
        assertThat(deleted.status()).isEqualTo("EXPIRED");
        assertThat(deleted.unconfirmedReason()).isEqualTo("AGENT_MISSING");
        assertThat(deleted.apiBaseUrl()).as("에이전트 행이 있으면 그 주소").isEqualTo("http://runtime.test/p/gone");

        Job missing = job(803, "orphan-child");
        assertThat(missing.status()).isEqualTo("EXPIRED");
        assertThat(missing.unconfirmedReason()).isEqualTo("AGENT_MISSING");
        assertThat(missing.apiBaseUrl()).as("에이전트 행이 없으면 빈 주소").isEmpty();
    }

    @Test
    @DisplayName("에이전트의 profile 이 부모와 다른 자식은 EXPIRED 와 PROFILE_CHANGED 로 들어온다")
    void expiresChildOfChangedProfile() throws SQLException {
        Job job = job(804, "moved-child");

        assertThat(job.status()).isEqualTo("EXPIRED");
        assertThat(job.unconfirmedReason()).isEqualTo("PROFILE_CHANGED");
        assertThat(job.profileName()).isEqualTo("old");
        assertThat(job.apiBaseUrl()).isEqualTo("http://runtime.test/p/changed");
    }

    @Test
    @DisplayName("같은 profile 의 같은 session 이 두 실행에 있으면 실행 번호가 작은 쪽 한 줄만 들어온다")
    void insertsOnlySmallestExecutionForSharedSession() throws SQLException {
        assertThat(executionsOf("shared-child")).containsExactly(805L);
        assertThat(executionsOf("waiting-child"))
                .as("다른 실행 아래 이미 줄이 있는 session 은 새로 넣지 않는다")
                .containsExactly(801L);
    }

    @Test
    @DisplayName("끝나지 않은 부모의 자식과 session 이 없는 시작 사건은 넣지 않는다")
    void skipsRunningParentAndSessionlessStart() throws SQLException {
        assertThat(executionsOf("running-child")).isEmpty();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM subagent_usage_job")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getInt(1))
                    .as("기존 셋에 new, gone, orphan, moved, shared 다섯을 더한 줄 수")
                    .isEqualTo(8);
        }
    }

    private void migrate(String version) {
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private void seed() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,
                        credential_scope, visibility, enabled, created_at, deleted_at)
                    VALUES (901, 'dad', 'Dad', 'dad', 'http://runtime.test/p/dad', 'SUBSCRIPTION',
                            'SHARED_HOUSEHOLD', 'PRIVATE', TRUE, CURRENT_TIMESTAMP(6), NULL),
                        (902, 'gone', 'Gone', 'gone', 'http://runtime.test/p/gone', 'SUBSCRIPTION',
                            'SHARED_HOUSEHOLD', 'PRIVATE', TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
                        (903, 'changed', 'Changed', 'changed', 'http://runtime.test/p/changed', 'SUBSCRIPTION',
                            'SHARED_HOUSEHOLD', 'PRIVATE', TRUE, CURRENT_TIMESTAMP(6), NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent_execution
                        (id, user_id, agent_id, profile_name, cost_mode, status, hermes_session_id,
                            started_at, finished_at)
                    VALUES
                        (801, 1, 901, 'dad', 'SUBSCRIPTION', 'SUCCEEDED', 'parent-801',
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:01:00'),
                        (802, 1, 902, 'gone', 'SUBSCRIPTION', 'SUCCEEDED', 'parent-802',
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:01:00'),
                        (803, 1, NULL, 'orphan', 'SUBSCRIPTION', 'SUCCEEDED', 'parent-803',
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:01:00'),
                        (804, 1, 903, 'old', 'SUBSCRIPTION', 'SUCCEEDED', 'parent-804',
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:01:00'),
                        (805, 1, 901, 'dad', 'SUBSCRIPTION', 'SUCCEEDED', 'parent-805',
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:01:00'),
                        (806, 1, 901, 'dad', 'SUBSCRIPTION', 'SUCCEEDED', 'parent-806',
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:01:00'),
                        (807, 1, 901, 'dad', 'SUBSCRIPTION', 'RUNNING', 'parent-807',
                            TIMESTAMP '2026-09-01 00:00:00', NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO execution_event (execution_id, sequence, event_type, hermes_session_id, occurred_at)
                    VALUES
                        (801, 1, 'SUBAGENT_STARTED', 'done-child', TIMESTAMP '2026-09-01 00:00:05'),
                        (801, 2, 'SUBAGENT_STARTED', 'new-child', TIMESTAMP '2026-09-01 00:00:20'),
                        (801, 3, 'SUBAGENT_STARTED', 'new-child', TIMESTAMP '2026-09-01 00:00:10'),
                        (801, 4, 'SUBAGENT_STARTED', NULL, TIMESTAMP '2026-09-01 00:00:30'),
                        (801, 5, 'SUBAGENT_COMPLETED', 'new-child', TIMESTAMP '2026-09-01 00:00:40'),
                        (802, 1, 'SUBAGENT_STARTED', 'gone-child', TIMESTAMP '2026-09-01 00:00:10'),
                        (803, 1, 'SUBAGENT_STARTED', 'orphan-child', TIMESTAMP '2026-09-01 00:00:10'),
                        (804, 1, 'SUBAGENT_STARTED', 'moved-child', TIMESTAMP '2026-09-01 00:00:10'),
                        (805, 1, 'SUBAGENT_STARTED', 'shared-child', TIMESTAMP '2026-09-01 00:00:10'),
                        (806, 1, 'SUBAGENT_STARTED', 'shared-child', TIMESTAMP '2026-09-01 00:00:10'),
                        (806, 2, 'SUBAGENT_STARTED', 'waiting-child', TIMESTAMP '2026-09-01 00:00:10'),
                        (807, 1, 'SUBAGENT_STARTED', 'running-child', TIMESTAMP '2026-09-01 00:00:10')
                    """);
            statement.executeUpdate("""
                    INSERT INTO subagent_usage_job
                        (execution_id, child_session_id, parent_session_id, profile_name, api_base_url, status,
                            unconfirmed_reason, created_at, next_attempt_at, expires_at, attempts, backoff_attempts)
                    VALUES
                        (801, 'done-child', 'parent-801', 'dad', 'http://runtime.test/p/dad', 'DONE', NULL,
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:05:00',
                            TIMESTAMP '2026-09-02 00:00:00', 3, 2),
                        (801, 'waiting-child', 'parent-801', 'dad', 'http://runtime.test/p/dad', 'WAITING', NULL,
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:05:00',
                            TIMESTAMP '2026-09-02 00:00:00', 3, 2),
                        (801, 'expired-child', 'parent-801', 'dad', 'http://runtime.test/p/dad', 'EXPIRED', 'DEADLINE',
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:05:00',
                            TIMESTAMP '2026-09-02 00:00:00', 3, 2)
                    """);
        }
    }

    /** H2 는 이름을 대문자로, MySQL 은 적은 그대로 둔다. */
    private static ResultSet column(Connection connection, String name) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        boolean upper = meta.storesUpperCaseIdentifiers();
        String table = upper ? "SUBAGENT_USAGE_JOB" : "subagent_usage_job";
        return meta.getColumns(connection.getCatalog(), null, table, upper ? name : name.toLowerCase(Locale.ROOT));
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }

    private static int columnSize(Connection connection, String name) throws SQLException {
        try (ResultSet column = column(connection, name)) {
            assertThat(column.next()).as("subagent_usage_job.%s 칸", name).isTrue();
            return column.getInt("COLUMN_SIZE");
        }
    }

    private int jobCount(long executionId, String childSessionId) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM subagent_usage_job WHERE execution_id = "
                        + executionId + " AND child_session_id = '" + childSessionId + "'")) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private List<Long> executionsOf(String childSessionId) throws SQLException {
        List<Long> result = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("SELECT execution_id FROM subagent_usage_job WHERE child_session_id = '"
                                + childSessionId + "' ORDER BY execution_id")) {
            while (rows.next()) {
                result.add(rows.getLong(1));
            }
        }
        return result;
    }

    private Job job(long executionId, String childSessionId) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("""
                        SELECT status, unconfirmed_reason, parent_session_id, profile_name, api_base_url,
                            created_at, next_attempt_at, expires_at, attempts, backoff_attempts, recorded_at
                        FROM subagent_usage_job
                        WHERE execution_id = %d AND child_session_id = '%s'
                        """.formatted(executionId, childSessionId))) {
            assertThat(row.next())
                    .as("실행 %d 의 자식 %s 작업 줄", executionId, childSessionId)
                    .isTrue();
            return new Job(
                    row.getString(1),
                    row.getString(2),
                    row.getString(3),
                    row.getString(4),
                    row.getString(5),
                    row.getTimestamp(6),
                    row.getTimestamp(7),
                    row.getTimestamp(8),
                    row.getInt(9),
                    row.getInt(10),
                    row.getTimestamp(11));
        }
    }

    private record Job(
            String status,
            String unconfirmedReason,
            String parentSessionId,
            String profileName,
            String apiBaseUrl,
            Timestamp createdAt,
            Timestamp nextAttemptAt,
            Timestamp expiresAt,
            int attempts,
            int backoffAttempts,
            Timestamp recordedAt) {}

    record Database(String url, String username, String password) {}
}
