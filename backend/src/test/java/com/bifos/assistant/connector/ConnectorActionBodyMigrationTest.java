package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** key 칸의 expand DDL은 기존 평문과 상태를 바꾸지 않는다. 암호화 이관은 애플리케이션이 맡는다. */
class ConnectorActionBodyMigrationTest {
    Database createDatabase() {
        return new Database("jdbc:h2:mem:action-body-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("본문 key 칸을 더해도 기존 승인 인자와 결과와 상태는 그대로 남는다")
    void expandsKeysWithoutChangingExistingBodies() throws Exception {
        var database = createDatabase();
        migrate(database, "20261011192647");
        try (var connection = DriverManager.getConnection(database.url(), database.username(), database.password());
            var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                + " VALUES (901, 'body@example.test', '검사 사용자', 1, 'MEMBER', CURRENT_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO connector_action (public_id, user_id, connector_id, hermes_tool, risk,"
                + " approval_mode, decision, passed, origin_execution_id, dedupe_key, args_sha256, created_at,"
                + " status, args_json, result_text) VALUES (X'019a0000000070008000000000000001', 901, 'demo',"
                + " 'mcp__demo__write', 'WRITE', 'REQUIRED', 'NEEDS_APPROVAL', FALSE, 1, '" + "a".repeat(64)
                + "', '" + "b".repeat(64) + "', CURRENT_TIMESTAMP(6), 'SUCCEEDED', '{}', '기존 결과')");
        }
        migrate(database, "20261012094254");
        try (var connection = DriverManager.getConnection(database.url(), database.username(), database.password());
            var sql = connection.createStatement();
            var row = sql.executeQuery("SELECT args_json, result_text, status, args_key_id, result_key_id FROM connector_action")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("{}");
            assertThat(row.getString(2)).isEqualTo("기존 결과");
            assertThat(row.getString(3)).isEqualTo("SUCCEEDED");
            assertThat(row.getObject(4)).isNull();
            assertThat(row.getObject(5)).isNull();
        }
    }


    private void migrate(Database database, String target) {
        Flyway.configure().dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration").target(target).load().migrate();
    }


    record Database(String url, String username, String password) {
    }
}
