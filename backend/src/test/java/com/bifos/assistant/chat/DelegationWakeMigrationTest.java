package com.bifos.assistant.chat;

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
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 맡긴 실행의 결과를 부모 대화에 전하는 데 쓰는 칸이 마이그레이션으로 생기는지 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다.
 */
class DelegationWakeMigrationTest {
    private String url;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:delegation-wake-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V37은 대화에 기본값 0 인 auto turn count 를 더한다")
    void v37AddsAutoTurnCountDefaultingToZero() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet column = meta.getColumns(null, null, "CONVERSATION", "AUTO_TURN_COUNT")) {
                assertThat(column.next()).as("conversation.auto_turn_count 칸").isTrue();
                assertThat(column.getString("IS_NULLABLE")).isEqualTo("NO");
                assertThat(column.getString("COLUMN_DEF")).isEqualTo("0");
            }
        }
    }

    @Test
    @DisplayName("V37은 이미 있던 대화의 auto turn count 를 0 으로 채운다")
    void v37FillsAutoTurnCountOfExistingConversationsWithZero() throws SQLException {
        String before = "jdbc:h2:mem:delegation-wake-before-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        // 공개 식별자 칸이 생기기 전 버전에서 대화를 넣으면 이후 마이그레이션이 그 칸을 채운다.
        Flyway.configure()
                .dataSource(before, "sa", "")
                .locations("classpath:db/migration")
                .target("22")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(before, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO conversation (id, user_id, title, created_at, updated_at)
                    VALUES (900, 1, 'before', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """);

            Flyway.configure()
                    .dataSource(before, "sa", "")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (ResultSet row = statement.executeQuery("SELECT auto_turn_count FROM conversation WHERE id = 900")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("V37은 이미 끝난 위임 실행만 전한 것으로 채우고 도는 위임과 위임이 아닌 실행은 비워 둔다")
    void v37MarksOnlyFinishedDelegationsAsDelivered() throws SQLException {
        String before = "jdbc:h2:mem:delegation-wake-backfill-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(before, "sa", "")
                .locations("classpath:db/migration")
                .target("36")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(before, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent_execution
                        (id, user_id, conversation_id, profile_name, cost_mode, status, delegation_key, started_at, finished_at)
                    VALUES
                        (901, 1, 1, 'worker', 'SUBSCRIPTION', 'SUCCEEDED', 'k-succeeded',
                            TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:01:00'),
                        (902, 1, 1, 'worker', 'SUBSCRIPTION', 'FAILED', 'k-failed-no-finish',
                            TIMESTAMP '2026-01-01 00:00:00', NULL),
                        (903, 1, 1, 'worker', 'SUBSCRIPTION', 'RUNNING', 'k-running',
                            TIMESTAMP '2026-01-01 00:00:00', NULL),
                        (904, 1, 1, 'dad', 'SUBSCRIPTION', 'SUCCEEDED', NULL,
                            TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:01:00')
                    """);

            Flyway.configure()
                    .dataSource(before, "sa", "")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            assertThat(deliveredAt(statement, 901))
                    .as("끝난 위임은 끝난 시각으로 채운다")
                    .isEqualTo(Timestamp.valueOf("2026-01-01 00:01:00"));
            assertThat(deliveredAt(statement, 902)).as("끝난 시각이 없는 끝난 위임도 채운다").isNotNull();
            assertThat(deliveredAt(statement, 903))
                    .as("도는 위임은 기동 정리 뒤에 전하도록 비워 둔다")
                    .isNull();
            assertThat(deliveredAt(statement, 904)).as("위임이 아닌 실행").isNull();
        }
    }

    private static Timestamp deliveredAt(Statement statement, long id) throws SQLException {
        try (ResultSet row =
                statement.executeQuery("SELECT result_delivered_at FROM agent_execution WHERE id = " + id)) {
            assertThat(row.next()).as("실행 %d", id).isTrue();
            return row.getTimestamp(1);
        }
    }

    @Test
    @DisplayName("V37은 실행에 비어 있어도 되는 result delivered at 과 대화별 전달 색인을 더한다")
    void v37AddsNullableResultDeliveredAtAndConversationIndex() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet column = meta.getColumns(null, null, "AGENT_EXECUTION", "RESULT_DELIVERED_AT")) {
                assertThat(column.next())
                        .as("agent_execution.result_delivered_at 칸")
                        .isTrue();
                assertThat(column.getString("IS_NULLABLE")).isEqualTo("YES");
            }
            assertThat(indexColumns(meta, "AGENT_EXECUTION", "IDX_AGENT_EXECUTION_CONVERSATION_DELIVERED"))
                    .containsExactly("CONVERSATION_ID", "RESULT_DELIVERED_AT", "STATUS");
        }
    }

    /** 그 색인의 칸을 색인 안 순서대로 돌려준다. */
    private static List<String> indexColumns(DatabaseMetaData meta, String table, String index) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rows = meta.getIndexInfo(null, null, table, false, false)) {
            while (rows.next()) {
                if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) {
                    result.add(rows.getShort("ORDINAL_POSITION") - 1, rows.getString("COLUMN_NAME"));
                }
            }
        }
        return result;
    }
}
