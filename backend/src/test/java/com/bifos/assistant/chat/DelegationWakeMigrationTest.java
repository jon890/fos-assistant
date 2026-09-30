package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 맡긴 실행의 결과를 부모 대화에 전하는 데 쓰는 칸이 마이그레이션으로 생기는지 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다.
 */
class DelegationWakeMigrationTest {
    private String url;

    @BeforeEach
    void 전체_마이그레이션을_적용한다() {
        url = "jdbc:h2:mem:delegation-wake-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
    }

    @Test
    void V37은_대화에_기본값_0_인_auto_turn_count_를_더한다() throws SQLException {
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
    void V37은_이미_있던_대화의_auto_turn_count_를_0_으로_채운다() throws SQLException {
        String before = "jdbc:h2:mem:delegation-wake-before-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        // 공개 식별자 칸이 생기기 전 버전에서 대화를 넣으면 이후 마이그레이션이 그 칸을 채운다.
        Flyway.configure().dataSource(before, "sa", "").locations("classpath:db/migration").target("22").load().migrate();
        try (Connection connection = DriverManager.getConnection(before, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO conversation (id, user_id, title, created_at, updated_at)
                    VALUES (900, 1, 'before', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """);

            Flyway.configure().dataSource(before, "sa", "").locations("classpath:db/migration").load().migrate();

            try (ResultSet row = statement.executeQuery("SELECT auto_turn_count FROM conversation WHERE id = 900")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isZero();
            }
        }
    }

    @Test
    void V37은_실행에_비어_있어도_되는_result_delivered_at_과_대화별_전달_색인을_더한다() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet column = meta.getColumns(null, null, "AGENT_EXECUTION", "RESULT_DELIVERED_AT")) {
                assertThat(column.next()).as("agent_execution.result_delivered_at 칸").isTrue();
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
