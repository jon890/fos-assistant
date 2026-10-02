package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V28 이 대화의 루트 session 칸과 실행 줄의 session, 위임 칸을 만들고 위임 키를 한 번만 받는지 본다.
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 지나지 않는다.
 */
class ExecutionDelegationColumnsMigrationTest {

    @Test
    @DisplayName("V28 이 만든 칸과 색인이 있고 위임 키는 한 번만 받되 빈 값은 여럿 받는다")
    void v28HasColumnsAndIndexAndAcceptsDelegationKeyOnceButManyBlanks() throws SQLException {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            DatabaseMetaData meta = connection.getMetaData();
            assertThat(columns(meta, "CONVERSATION")).contains("HERMES_ROOT_SESSION_ID");
            assertThat(columns(meta, "AGENT_EXECUTION")).contains("HERMES_SESSION_ID", "DELEGATION_KEY", "OUTPUT_TEXT");
            assertThat(indexes(meta, "AGENT_EXECUTION"))
                    .contains("UK_AGENT_EXECUTION_DELEGATION_KEY", "IDX_AGENT_EXECUTION_SESSION_STATUS");

            statement.executeUpdate(insert("'key-1'", "'fos-a'"));
            // 위임하지 않은 실행은 키를 비워 둔다. 빈 값은 여럿이어도 된다.
            statement.executeUpdate(insert("NULL", "'fos-a'"));
            statement.executeUpdate(insert("NULL", "NULL"));

            assertThatThrownBy(() -> statement.executeUpdate(insert("'key-1'", "'fos-b'")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UK_AGENT_EXECUTION_DELEGATION_KEY");
            try (ResultSet rows =
                    statement.executeQuery("SELECT COUNT(*) FROM agent_execution WHERE hermes_session_id = 'fos-a'")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(2);
            }
        }
    }

    private static List<String> columns(DatabaseMetaData meta, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rows = meta.getColumns(null, null, table, null)) {
            while (rows.next()) {
                names.add(rows.getString("COLUMN_NAME"));
            }
        }
        return names;
    }

    private static List<String> indexes(DatabaseMetaData meta, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rows = meta.getIndexInfo(null, null, table, false, false)) {
            while (rows.next()) {
                names.add(rows.getString("INDEX_NAME"));
            }
        }
        return names;
    }

    private static String insert(String delegationKey, String sessionId) {
        return "INSERT INTO agent_execution (user_id, conversation_id, profile_name, cost_mode, status, "
                + "started_at, hermes_session_id, delegation_key, output_text) "
                + "VALUES (1, 1, 'p', 'API', 'RUNNING', CURRENT_TIMESTAMP(6), " + sessionId + ", "
                + delegationKey + ", '답')";
    }
}
