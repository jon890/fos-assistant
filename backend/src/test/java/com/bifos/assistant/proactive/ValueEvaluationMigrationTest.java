package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValueEvaluationMigrationTest {

    @Test
    @DisplayName("마이그레이션으로 평가 JSON 이 저장되고 살펴보기를 지우면 replay 도 함께 지워진다")
    void persistsEvidenceAndCascadesAllAttempts() throws Exception {
        String url = "jdbc:h2:mem:value-evaluation-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                    + " VALUES (931, 'user@example.com', '사용자A', 1, 'MEMBER', CURRENT_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode, credential_scope,"
                    + " visibility, owner_user_id, enabled, created_at) VALUES (932, 'a', 'n', 'a', 'http://localhost',"
                    + " 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', 931, TRUE, CURRENT_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO conversation (id, public_id, user_id, agent_id, title, created_at, updated_at)"
                    + " VALUES (933, X'00000000000000000000000000000933', 931, 932, '평가', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO proactive_check (id, user_id, agent_id, conversation_id, trigger_type, status, started_at)"
                    + " VALUES (934, 931, 932, 933, 'MANUAL', 'SUCCEEDED', CURRENT_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO proactive_value_evaluation (id, check_id, user_id, outcome, evidence_json, created_at)"
                    + " VALUES (935, 934, 931, 'EMPTY', '{\"state\":{\"version\":1}}', CURRENT_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO proactive_value_evaluation (id, check_id, user_id, replay_of_id, outcome, evidence_json, created_at)"
                    + " VALUES (936, 934, 931, 935, 'EMPTY', '{\"state\":{\"version\":1}}', CURRENT_TIMESTAMP(6))");
            try (ResultSet rows = sql.executeQuery("SELECT evidence_json FROM proactive_value_evaluation WHERE id = 936")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).contains("version");
            }
            sql.executeUpdate("DELETE FROM proactive_check WHERE id = 934");
            try (ResultSet count = sql.executeQuery("SELECT COUNT(*) FROM proactive_value_evaluation")) {
                assertThat(count.next()).isTrue();
                assertThat(count.getInt(1)).isZero();
            }
        }
    }
}
