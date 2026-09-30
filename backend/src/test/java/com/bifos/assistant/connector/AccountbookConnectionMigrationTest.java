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
import org.junit.jupiter.api.Test;

class AccountbookConnectionMigrationTest {
    private String url;

    @BeforeEach
    void 전체_마이그레이션을_적용한다() {
        url = "jdbc:h2:mem:accountbook-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
    }

    @Test
    void V36은_연결_상태만_저장하고_raw_token과_hash_칸을_만들지_않는다() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            List<String> columns = columns(connection.getMetaData(), "ACCOUNTBOOK_CONNECTION");

            assertThat(columns).contains("USER_ID", "AGENT_ID", "STATUS", "TOKEN_PREFIX", "FAMILY_UUID", "DESIRED_ENABLED");
            assertThat(columns).noneMatch(column -> column.contains("TOKEN") && !column.equals("TOKEN_PREFIX"));
            assertThat(columns).noneMatch(column -> column.contains("HASH"));
        }
    }

    @Test
    void V36은_사용자와_에이전트_FK와_에이전트_유니크_키를_만든다() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData meta = connection.getMetaData();

            assertThat(importedColumns(meta, "ACCOUNTBOOK_CONNECTION")).contains("USER_ID", "AGENT_ID");
            assertThat(hasUniqueIndexFor(meta, "ACCOUNTBOOK_CONNECTION", "AGENT_ID")).isTrue();
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
