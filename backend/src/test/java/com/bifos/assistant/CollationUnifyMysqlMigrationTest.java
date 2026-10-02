package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 정렬 규칙을 적지 않고 만든 표 여덟을 {@code utf8mb4_0900_ai_ci} 로 맞추는 마이그레이션(V58 부터 V65)을 줄이 있는 실제 MySQL 에서 본다.
 *
 * <p>H2 는 {@code CONVERT TO} 를 받고 아무것도 바꾸지 않아 이 검사를 H2 로 돌릴 수 없다. 끝 번호를 적어 두는 까닭은 뒤에 생기는
 * 마이그레이션이 이 검사가 넣은 줄에 걸리지 않게 하려는 것이다.
 */
@Tag("mysql")
class CollationUnifyMysqlMigrationTest {

    private static final String OLD_COLLATION = "utf8mb4_unicode_ci";
    private static final String NEW_COLLATION = "utf8mb4_0900_ai_ci";
    private static final String BEFORE_VERSION = "57";
    private static final String LAST_VERSION = "65";

    /** 마이그레이션 번호 순서다. V58 이 첫 표를, V65 가 마지막 표를 바꾼다. */
    private static final List<String> TABLES = List.of(
            "agent_token",
            "chat_pending_message",
            "execution_event",
            "memory",
            "model_hidden",
            "model_tier_definition",
            "model_tier_group_setting",
            "subagent_usage_job");

    private static final String TOKEN_HASH = "AbCdEf0123456789aBcDeF";
    private static final String MEMORY_CONTENT = "아침에는 커피보다 보리차를 마신다.";
    private static final String PENDING_CONTENT = "내일 장 볼 것을 정리해 줘.";

    /** U+1DC4 는 옛 정렬 규칙에서 무게가 있고 새 정렬 규칙에서 없다. 그래서 새 정렬 규칙에서만 {@code 'a'} 와 같다. */
    private static final String COMBINED_A = "a\u1DC4";

    private MysqlTestDatabase database;

    @BeforeEach
    void setUp() {
        database = MysqlTestDatabase.create();
        migrate(BEFORE_VERSION);
    }

    @Test
    @DisplayName("줄이 있는 표 여덟의 정렬 규칙만 바뀐다")
    void convertsOnlyCollationOfTablesWithRows() throws SQLException {
        seedEveryTable();
        List<Shape> before = new ArrayList<>();
        for (String table : TABLES) {
            assertThat(tableCollation(table)).as("올리기 전 %s 의 정렬 규칙", table).isEqualTo(OLD_COLLATION);
            before.add(shape(table));
        }

        migrate(LAST_VERSION);

        for (int i = 0; i < TABLES.size(); i++) {
            String table = TABLES.get(i);
            assertThat(tableCollation(table)).as("올린 뒤 %s 의 정렬 규칙", table).isEqualTo(NEW_COLLATION);
            assertThat(columnCollations(table))
                    .as("올린 뒤 %s 의 문자열 칸 정렬 규칙", table)
                    .containsOnly(NEW_COLLATION);
            Shape after = shape(table);
            assertThat(after.rows())
                    .as("%s 의 줄 수", table)
                    .isEqualTo(before.get(i).rows());
            assertThat(after.rows()).as("%s 에 넣은 줄이 있다", table).isPositive();
            assertThat(after.columns())
                    .as("%s 의 칸 이름과 타입과 NULL 허용과 기본값과 extra", table)
                    .isEqualTo(before.get(i).columns());
            assertThat(after.indexes())
                    .as("%s 의 색인", table)
                    .isEqualTo(before.get(i).indexes());
        }
        assertThat(strings("SELECT token_hash FROM agent_token"))
                .as("대소문자가 섞인 token_hash")
                .containsExactly(TOKEN_HASH);
        assertThat(strings("SELECT content FROM memory")).as("memory 의 한글 본문").containsExactly(MEMORY_CONTENT);
        assertThat(strings("SELECT content FROM chat_pending_message"))
                .as("chat_pending_message 의 한글 본문")
                .containsExactly(PENDING_CONTENT);
    }

    @Test
    @DisplayName("새 정렬 규칙에서 겹치는 줄이 있으면 그 표에서 멈춘다")
    void stopsAtTableWithRowsThatCollideUnderNewCollation() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO model_hidden (group_id, provider, model) VALUES (1, ?, 'same-model')")) {
            for (String provider : List.of("a", COMBINED_A)) {
                insert.setString(1, provider);
                insert.executeUpdate();
            }
        }
        assertThat(strings("SELECT HEX(provider) FROM model_hidden ORDER BY id"))
                .as("결합 문자가 ? 로 바뀌지 않고 utf8mb4 로 들어갔다")
                .containsExactly("61", "61E1B784");

        assertThatThrownBy(() -> migrate(LAST_VERSION))
                .isInstanceOf(FlywayException.class)
                .satisfies(e -> assertThat(sqlErrorCodes(e))
                        .as("실패 원인은 유일 색인 겹침(MySQL 오류 1062)이다")
                        .contains(1062));

        assertThat(tableCollation("model_hidden"))
                .as("실패한 model_hidden 의 정렬 규칙")
                .isEqualTo(OLD_COLLATION);
        assertThat(strings("SELECT HEX(provider) FROM model_hidden ORDER BY id"))
                .as("실패한 model_hidden 의 줄")
                .containsExactly("61", "61E1B784");
        for (String table : TABLES.subList(0, 4)) {
            assertThat(tableCollation(table)).as("실패한 표 앞의 %s", table).isEqualTo(NEW_COLLATION);
        }
        for (String table : TABLES.subList(5, 8)) {
            assertThat(tableCollation(table)).as("실패한 표 뒤의 %s", table).isEqualTo(OLD_COLLATION);
        }
        assertThat(
                        strings(
                                "SELECT CAST(MAX(CAST(version AS UNSIGNED)) AS CHAR) FROM flyway_schema_history WHERE success = 1"))
                .as("성공으로 기록된 가장 큰 번호")
                .containsExactly("61");
    }

    /** 원인 사슬에 든 {@link SQLException} 의 오류 번호를 모은다. */
    private static List<Integer> sqlErrorCodes(Throwable thrown) {
        List<Integer> codes = new ArrayList<>();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                codes.add(sql.getErrorCode());
            }
        }
        return codes;
    }

    private void migrate(String version) {
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private void seedEveryTable() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO agent_token (token_hash, label, created_at) VALUES ('" + TOKEN_HASH
                    + "', '집 비서', TIMESTAMP '2026-09-01 00:00:00')");
            statement.executeUpdate("INSERT INTO chat_pending_message (conversation_id, user_id, content, created_at)"
                    + " VALUES (1, 1, '" + PENDING_CONTENT + "', TIMESTAMP '2026-09-01 00:00:00')");
            statement.executeUpdate("""
                    INSERT INTO execution_event
                        (execution_id, sequence, event_type, hermes_session_id, completed_child_session_id, occurred_at)
                    VALUES
                        (1, 1, 'SUBAGENT_STARTED', 'child-1', NULL, TIMESTAMP '2026-09-01 00:00:00'),
                        (1, 2, 'SUBAGENT_COMPLETED', 'child-1', 'child-1', TIMESTAMP '2026-09-01 00:00:10')
                    """);
            statement.executeUpdate("""
                    INSERT INTO memory
                        (scope, owner_user_id, title, content, status, proposal_dedup_key, document_key,
                            source_type, source_ref, created_at, updated_at)
                    VALUES ('USER', 1, '마실 것', '%s', 'ACCEPTED', 'dedup-1', 'drink', 'NOTE', 'notes/drink.md',
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:00:00')
                    """.formatted(MEMORY_CONTENT));
            statement.executeUpdate(
                    "INSERT INTO model_hidden (group_id, provider, model) VALUES (1, 'provider-a', 'model-a')");
            statement.executeUpdate("INSERT INTO model_tier_definition (group_id, tier, provider, model)"
                    + " VALUES (1, 'FAST', 'provider-a', 'model-a')");
            statement.executeUpdate("INSERT INTO model_tier_group_setting (group_id, default_tier) VALUES (1, 'FAST')");
            statement.executeUpdate("""
                    INSERT INTO subagent_usage_job
                        (execution_id, child_session_id, parent_session_id, profile_name, api_base_url, status,
                            created_at, next_attempt_at, expires_at)
                    VALUES (1, 'child-1', 'parent-1', 'dad', 'http://runtime.test/p/dad', 'WAITING',
                            TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:05:00',
                            TIMESTAMP '2026-09-02 00:00:00')
                    """);
        }
    }

    private Shape shape(String table) throws SQLException {
        long rows = Long.parseLong(
                strings("SELECT CAST(COUNT(*) AS CHAR) FROM " + table).get(0));
        List<String> columns = strings("""
                SELECT CONCAT(column_name, ' ', column_type, ' ', is_nullable,
                    ' default=', COALESCE(column_default, '<NULL>'), ' extra=', COALESCE(extra, '<NULL>'))
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = '%s'
                ORDER BY ordinal_position
                """.formatted(table));
        List<String> indexes = strings("""
                SELECT CONCAT(index_name, ' ', seq_in_index, ' ', column_name, ' ', non_unique)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = '%s'
                ORDER BY index_name, seq_in_index
                """.formatted(table));
        return new Shape(rows, columns, indexes);
    }

    private String tableCollation(String table) throws SQLException {
        return strings("""
                SELECT table_collation FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = '%s'
                """.formatted(table)).get(0);
    }

    private List<String> columnCollations(String table) throws SQLException {
        return strings("""
                SELECT DISTINCT collation_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = '%s' AND collation_name IS NOT NULL
                """.formatted(table));
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }

    private record Shape(long rows, List<String> columns, List<String> indexes) {}
}
