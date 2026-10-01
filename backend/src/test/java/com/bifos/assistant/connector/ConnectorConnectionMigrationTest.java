package com.bifos.assistant.connector;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가계부 전용 표의 행이 V38 로 범용 표에 그대로 옮겨지는지 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다. 옛 표에 행을 넣으려고
 * V37 까지만 올린 뒤 V38 을 올린다.
 */
class ConnectorConnectionMigrationTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String FAMILY = "11111111-2222-3333-4444-555555555555";
    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:connector-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        migrate("37");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            for (int id = 901; id <= 903; id++) {
                statement.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                        + " VALUES (" + id + ", 'u" + id + "@example.com', 'u" + id + "', 1, 'MEMBER',"
                        + " CURRENT_TIMESTAMP(6))");
                statement.executeUpdate("INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,"
                        + " credential_scope, visibility, owner_user_id, enabled, created_at, connector_managed)"
                        + " VALUES (" + id + ", 'c" + id + "', 'n', 'p" + id + "', 'http://localhost',"
                        + " 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', " + id + ", FALSE,"
                        + " CURRENT_TIMESTAMP(6), TRUE)");
            }
            // 토큰과 가족이 모두 있는 연결, 가족을 고르지 않은 연결, 해제되어 둘 다 비운 연결이다.
            statement.executeUpdate(oldRow(901, "READY", "'" + FAMILY + "'", "'fab_abcd'", false, true, true));
            statement.executeUpdate(oldRow(902, "PENDING", "NULL", "'fab_efgh'", true, true, true));
            statement.executeUpdate(oldRow(903, "DISCONNECTED", "NULL", "NULL", true, false, false));
        }
        migrate("38");
    }

    @Test
    @DisplayName("V38은 토큰 앞부분과 가족이 있는 행의 상태와 칸을 그대로 옮긴다")
    void v38MovesRowWithTokenPrefixAndFamily() throws Exception {
        MovedRow row = movedRow(901);

        assertThat(row.connectorId()).isEqualTo("fos-accountbook");
        assertThat(row.agentId()).isEqualTo(901L);
        assertThat(row.status()).isEqualTo("READY");
        assertThat(row.restartRequired()).isFalse();
        assertThat(row.desiredEnabled()).isTrue();
        assertThat(row.checked()).isTrue();
        assertThat(MAPPER.readTree(row.fields()))
                .isEqualTo(MAPPER.readTree(
                        "{\"values\":{\"family\":\"" + FAMILY + "\"},\"secretPrefixes\":{\"token\":\"fab_abcd\"}}"));
    }

    @Test
    @DisplayName("V38은 가족이 없는 행에 family 키를 넣지 않고 재시작 대기를 유지한다")
    void v38OmitsFamilyKeyWhenFamilyIsNull() throws Exception {
        MovedRow row = movedRow(902);

        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.restartRequired()).isTrue();
        assertThat(row.desiredEnabled()).isTrue();
        assertThat(MAPPER.readTree(row.fields()))
                .isEqualTo(MAPPER.readTree("{\"values\":{},\"secretPrefixes\":{\"token\":\"fab_efgh\"}}"));
    }

    @Test
    @DisplayName("V38은 해제되어 둘 다 비운 행을 빈 칸으로 옮긴다")
    void v38MovesDisconnectedRowWithEmptyFields() throws Exception {
        MovedRow row = movedRow(903);

        assertThat(row.status()).isEqualTo("DISCONNECTED");
        assertThat(row.restartRequired()).isTrue();
        assertThat(row.desiredEnabled()).isFalse();
        assertThat(row.checked()).isFalse();
        assertThat(MAPPER.readTree(row.fields())).isEqualTo(MAPPER.readTree("{\"values\":{},\"secretPrefixes\":{}}"));
    }

    @Test
    @DisplayName("V38은 옛 표를 지우고 raw token 과 hash 칸이 없는 새 표를 만든다")
    void v38DropsOldTableAndStoresNoRawTokenOrHashColumns() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData meta = connection.getMetaData();

            assertThat(columns(meta, "ACCOUNTBOOK_CONNECTION")).isEmpty();
            assertThat(columns(meta, "CONNECTOR_CONNECTION"))
                    .containsExactlyInAnyOrder(
                            "ID",
                            "USER_ID",
                            "CONNECTOR_ID",
                            "AGENT_ID",
                            "STATUS",
                            "FIELDS",
                            "RESTART_REQUIRED",
                            "DESIRED_ENABLED",
                            "CHECKED_AT",
                            "CREATED_AT",
                            "UPDATED_AT");
        }
    }

    @Test
    @DisplayName("V38은 사용자와 에이전트 FK, 에이전트 유니크 키, 사용자와 커넥터의 유니크 키를 만든다")
    void v38CreatesForeignKeysAndUniqueKeys() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData meta = connection.getMetaData();

            assertThat(importedColumns(meta, "CONNECTOR_CONNECTION")).contains("USER_ID", "AGENT_ID");
            assertThat(uniqueIndexes(meta, "CONNECTOR_CONNECTION"))
                    .contains(List.of("AGENT_ID"), List.of("USER_ID", "CONNECTOR_ID"));
        }
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private static String oldRow(
            int id, String status, String family, String prefix, boolean restart, boolean desired, boolean checked) {
        return "INSERT INTO accountbook_connection (user_id, agent_id, status, family_uuid, token_prefix,"
                + " restart_required, desired_enabled, checked_at, created_at, updated_at) VALUES ("
                + id + ", " + id + ", '" + status + "', " + family + ", " + prefix + ", " + restart + ", " + desired
                + ", " + (checked ? "CURRENT_TIMESTAMP(6)" : "NULL") + ", CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))";
    }

    private record MovedRow(
            String connectorId,
            long agentId,
            String status,
            String fields,
            boolean restartRequired,
            boolean desiredEnabled,
            boolean checked) {}

    private MovedRow movedRow(int userId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT connector_id, agent_id, status, fields,"
                        + " restart_required, desired_enabled, checked_at, created_at, updated_at"
                        + " FROM connector_connection WHERE user_id = " + userId)) {
            assertThat(row.next()).as("사용자 %d 의 옮겨진 행", userId).isTrue();
            assertThat(row.getTimestamp("created_at")).isNotNull();
            assertThat(row.getTimestamp("updated_at")).isNotNull();
            MovedRow moved = new MovedRow(
                    row.getString("connector_id"),
                    row.getLong("agent_id"),
                    row.getString("status"),
                    row.getString("fields"),
                    row.getBoolean("restart_required"),
                    row.getBoolean("desired_enabled"),
                    row.getTimestamp("checked_at") != null);
            assertThat(row.next()).as("사용자 %d 의 행은 하나다", userId).isFalse();
            return moved;
        }
    }

    private static List<String> columns(DatabaseMetaData meta, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rows = meta.getColumns(null, null, table, null)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    private static List<String> importedColumns(DatabaseMetaData meta, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rows = meta.getImportedKeys(null, null, table)) {
            while (rows.next()) {
                result.add(rows.getString("FKCOLUMN_NAME"));
            }
        }
        return result;
    }

    /** 유니크 인덱스마다 그 칸 이름을 순서대로 모은다. */
    private static List<List<String>> uniqueIndexes(DatabaseMetaData meta, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        List<List<String>> result = new ArrayList<>();
        try (ResultSet rows = meta.getIndexInfo(null, null, table, true, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (!names.contains(name)) {
                    names.add(name);
                    result.add(new ArrayList<>());
                }
                result.get(names.indexOf(name)).add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }
}
