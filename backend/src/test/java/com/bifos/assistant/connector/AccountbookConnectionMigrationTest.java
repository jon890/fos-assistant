package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountbookConnectionMigrationTest {
    private String url;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:accountbook-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V36은 연결 상태만 저장하고 raw token과 hash 칸을 만들지 않는다")
    void v36StoresOnlyStateWithoutRawTokenOrHashColumns() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            List<String> columns = columns(connection.getMetaData(), "ACCOUNTBOOK_CONNECTION");

            assertThat(columns)
                    .contains("USER_ID", "AGENT_ID", "STATUS", "TOKEN_PREFIX", "FAMILY_UUID", "DESIRED_ENABLED");
            assertThat(columns).noneMatch(column -> column.contains("TOKEN") && !column.equals("TOKEN_PREFIX"));
            assertThat(columns).noneMatch(column -> column.contains("HASH"));
        }
    }

    @Test
    @DisplayName("V36은 사용자와 에이전트 FK와 에이전트 유니크 키를 만든다")
    void v36CreatesUserAndAgentForeignKeysAndAgentUniqueKey() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData meta = connection.getMetaData();

            assertThat(importedColumns(meta, "ACCOUNTBOOK_CONNECTION")).contains("USER_ID", "AGENT_ID");
            assertThat(hasUniqueIndexFor(meta, "ACCOUNTBOOK_CONNECTION", "AGENT_ID"))
                    .isTrue();
        }
    }

    private static List<String> columns(DatabaseMetaData meta, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rows = meta.getColumns(null, null, table, null)) {
            while (rows.next()) result.add(rows.getString("COLUMN_NAME"));
        }
        return result;
    }

    private static List<String> importedColumns(DatabaseMetaData meta, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rows = meta.getImportedKeys(null, null, table)) {
            while (rows.next()) result.add(rows.getString("FKCOLUMN_NAME"));
        }
        return result;
    }

    private static boolean hasUniqueIndexFor(DatabaseMetaData meta, String table, String column) throws SQLException {
        try (ResultSet rows = meta.getIndexInfo(null, null, table, false, false)) {
            while (rows.next()) {
                if (!rows.getBoolean("NON_UNIQUE") && column.equals(rows.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }
}
