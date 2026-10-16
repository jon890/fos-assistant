package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실행 기록의 선택 근거 칸(ADR-20261015 / memory-eval-gate)을 더하는 마이그레이션이 다섯 칸을 비울 수 있게 만드는지 본다.
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 지나지 않는다.
 */
class ExecutionContextSourceSelectionMigrationTest {

    @Test
    @DisplayName("선택 근거 다섯 칸이 있고 비어 있는 줄과 채운 줄을 모두 받는다")
    void addsNullableSelectionColumns() throws SQLException {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
            Statement statement = connection.createStatement()) {
            Map<String, Integer> nullable = nullableColumns(connection.getMetaData(), "EXECUTION_CONTEXT_SOURCE");
            for (String column : new String[] {
                "SELECTOR", "SELECTION_RANK", "KEYWORD_SCORE", "SEMANTIC_SCORE", "SELECTION_SCORE"}) {
                assertThat(nullable).as(column).containsEntry(column, DatabaseMetaData.columnNullable);
            }

            statement.executeUpdate("INSERT INTO execution_context_source "
                + "(execution_id, position, source, source_ref, body_mode, freshness, created_at) "
                + "VALUES (1, 0, 'MEMORY_READ', 'memory:1', 'INLINE', 'UNKNOWN', CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO execution_context_source "
                + "(execution_id, position, source, source_ref, body_mode, freshness, selector, selection_rank, "
                + "keyword_score, semantic_score, selection_score, created_at) "
                + "VALUES (1, 1, 'MEMORY_INDEX', 'memory:2', 'TITLE_ONLY', 'FRESH', 'index-id-v1', 1, 0.5, 0.25, 0.75, "
                + "CURRENT_TIMESTAMP)");
            try (ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM execution_context_source WHERE selector IS NULL AND selection_rank IS NULL")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
    }


    private static Map<String, Integer> nullableColumns(DatabaseMetaData meta, String table) throws SQLException {
        Map<String, Integer> nullable = new HashMap<>();
        try (ResultSet columns = meta.getColumns(null, null, table, null)) {
            while (columns.next()) {
                nullable.put(columns.getString("COLUMN_NAME"), columns.getInt("NULLABLE"));
            }
        }
        return nullable;
    }
}
