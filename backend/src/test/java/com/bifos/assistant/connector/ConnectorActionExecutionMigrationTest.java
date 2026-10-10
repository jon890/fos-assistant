package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** 실제 Flyway DDL의 NULL, 유일 제약, 이력 수명과 인덱스를 H2에서 검사한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectorActionExecutionMigrationTest {
    private String url;

    @BeforeAll
    void migrate() throws SQLException {
        url = "jdbc:h2:mem:financial-execution-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .outOfOrder(true)
                .load()
                .migrate();
        execute("INSERT INTO app_user (id,email,display_name,group_id,role,created_at)"
                + " VALUES (901,'financial@example.com','주인',1,'MEMBER',CURRENT_TIMESTAMP(6))");
    }

    @BeforeEach
    void actions() throws SQLException {
        execute("DELETE FROM connector_action_execution");
        execute("DELETE FROM connector_action");
        for (int id = 1; id <= 3; id++) {
            execute("INSERT INTO connector_action"
                    + " (id,public_id,user_id,connector_id,hermes_tool,risk,approval_mode,decision,passed,"
                    + "origin_execution_id,dedupe_key,args_sha256,created_at) VALUES (" + id
                    + ",X'0000000000000000000000000000000" + id
                    + "',901,'demo-financial','mcp__demo__place_order','FINANCIAL','ALWAYS','DENIED',FALSE,1,'"
                    + String.valueOf(id).repeat(64) + "','" + "a".repeat(64) + "',CURRENT_TIMESTAMP(6))");
        }
    }

    @Test
    @DisplayName("저장 컬럼, NULL 여부와 본문 타입을 실제 DDL에서 확인한다")
    void columnContract() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                ResultSet columns =
                        connection.getMetaData().getColumns(null, null, "CONNECTOR_ACTION_EXECUTION", null)) {
            Map<String, Integer> nullable = new HashMap<>();
            Map<String, Integer> sizes = new HashMap<>();
            while (columns.next()) {
                nullable.put(columns.getString("COLUMN_NAME"), columns.getInt("NULLABLE"));
                sizes.put(columns.getString("COLUMN_NAME"), columns.getInt("COLUMN_SIZE"));
            }
            assertThat(nullable).hasSize(18);
            for (String name : new String[] {
                "CONTENT_KEY_ID", "SUPERSEDES_UNKNOWN_ACTION_ID", "TICKET_ID", "TICKET_EXPIRES_AT", "CONSUMED_AT"
            }) {
                assertThat(nullable.get(name)).as(name).isEqualTo(DatabaseMetaData.columnNullable);
            }
            for (String name : new String[] {
                "ACTION_ID",
                "CONNECTION_ID",
                "BINDING_ID",
                "CONNECTION_UPDATED_AT",
                "BINDING_UPDATED_AT",
                "EXECUTION_ARGS_JSON",
                "SUMMARY_JSON",
                "SCOPE_JSON",
                "EXECUTION_ARGS_SHA256",
                "SCOPE_SHA256",
                "REQUEST_KEY",
                "PROTOCOL",
                "CREATED_AT"
            }) {
                assertThat(nullable.get(name)).as(name).isEqualTo(DatabaseMetaData.columnNoNulls);
            }
            assertThat(sizes.get("TICKET_ID")).isEqualTo(16);
            assertThat(sizes.get("EXECUTION_ARGS_SHA256")).isEqualTo(64);
            assertThat(sizes.get("REQUEST_KEY")).isEqualTo(64);
        }
    }

    @Test
    @DisplayName("action PK와 ticket UUID UNIQUE는 중복을 막고 NULL ticket은 여러 줄을 허용한다")
    void primaryKeyAndNullableTicketUnique() throws SQLException {
        execute(row(1, "NULL", "NULL"));
        execute(row(2, "NULL", "NULL"));
        assertThatThrownBy(() -> execute(row(1, "NULL", "NULL"))).isInstanceOf(SQLException.class);
        execute(
                "UPDATE connector_action_execution SET ticket_id = X'00000000000040008000000000000001' WHERE action_id = 1");
        assertThatThrownBy(() -> execute("UPDATE connector_action_execution"
                        + " SET ticket_id = X'00000000000040008000000000000001' WHERE action_id = 2"))
                .isInstanceOf(SQLException.class);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    @DisplayName("supersedes는 선조 FK 없이 이력 번호를 예약하고 중복 선조만 막는다")
    void supersedesIsNullableUniqueHistoryWithoutForeignKey() throws SQLException {
        execute(row(1, "NULL", "9999"));
        execute(row(2, "NULL", "NULL"));
        assertThatThrownBy(
                        () -> execute(
                                "UPDATE connector_action_execution SET supersedes_unknown_action_id = 9999 WHERE action_id = 2"))
                .isInstanceOf(SQLException.class);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                ResultSet imported =
                        connection.getMetaData().getImportedKeys(null, null, "CONNECTOR_ACTION_EXECUTION")) {
            assertThat(imported.next()).isTrue();
            assertThat(imported.getString("FKCOLUMN_NAME")).isEqualTo("ACTION_ID");
            assertThat(imported.getString("PKTABLE_NAME")).isEqualTo("CONNECTOR_ACTION");
            assertThat(imported.getInt("DELETE_RULE")).isEqualTo(DatabaseMetaData.importedKeyCascade);
            assertThat(imported.next()).isFalse();
        }
    }

    @Test
    @DisplayName("없는 action은 거절하고 action을 지우면 연결된 실행 내용도 지운다")
    void actionForeignKeyAndCascade() throws SQLException {
        assertThatThrownBy(() -> execute(row(99, "NULL", "NULL"))).isInstanceOf(SQLException.class);
        execute(row(1, "NULL", "NULL"));
        execute("DELETE FROM connector_action WHERE id = 1");
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("request_key는 중복을 허용하는 일반 조회 인덱스다")
    void requestKeyIndexIsNotUnique() throws SQLException {
        execute(row(1, "NULL", "NULL"));
        execute(row(2, "NULL", "NULL"));
        assertThat(count()).isEqualTo(2);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                ResultSet indexes =
                        connection.getMetaData().getIndexInfo(null, null, "CONNECTOR_ACTION_EXECUTION", false, false)) {
            boolean found = false;
            while (indexes.next()) {
                if ("IDX_CONNECTOR_ACTION_EXECUTION_REQUEST".equals(indexes.getString("INDEX_NAME"))) {
                    found = true;
                    assertThat(indexes.getBoolean("NON_UNIQUE")).isTrue();
                    assertThat(indexes.getString("COLUMN_NAME")).isEqualTo("REQUEST_KEY");
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    @DisplayName("필수 본문과 두 revision의 NULL은 거절한다")
    void mandatoryValuesCannotBeNull() {
        for (String value : new String[] {"'{}'", "CURRENT_TIMESTAMP(6)"}) {
            assertThatThrownBy(() -> execute(row(1, "NULL", "NULL").replace(value, "NULL")))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static String row(int action, String ticket, String previous) {
        return "INSERT INTO connector_action_execution (action_id,connection_id,binding_id,connection_updated_at,"
                + "binding_updated_at,execution_args_json,summary_json,scope_json,execution_args_sha256,scope_sha256,"
                + "request_key,supersedes_unknown_action_id,protocol,ticket_id,created_at) VALUES (" + action
                + ",701,801,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'{}','{}','{}','" + "a".repeat(64) + "','"
                + "b".repeat(64) + "','" + "c".repeat(64) + "'," + previous + ",'approval-claim-v1'," + ticket
                + ",CURRENT_TIMESTAMP(6))";
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private int count() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM connector_action_execution")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
