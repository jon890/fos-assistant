package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AutonomyMigrationTest {

    private static final String DECISION = "INSERT INTO proactive_autonomy_decision (id, user_id, evaluation_id,"
            + " candidate_id, source_check_id, action_level, reasons_json, inputs_json, policy_version, execution_key,"
            + " execution_status, execution_check_id, created_at) VALUES ";

    @Test
    @DisplayName("실행 키는 원천마다 하나이고, 시작한 살펴보기를 지우면 비우고 평가를 지우면 판정도 지운다")
    void executionKeyIsUniqueAndCascades() throws Exception {
        String url = "jdbc:h2:mem:autonomy-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                    + " VALUES (941, 'user@example.com', '사용자A', 1, 'MEMBER', CURRENT_TIMESTAMP(6))");
            sql.executeUpdate(
                    "INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode, credential_scope,"
                            + " visibility, owner_user_id, enabled, created_at) VALUES (942, 'a', 'n', 'a', 'http://localhost',"
                            + " 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', 941, TRUE, CURRENT_TIMESTAMP(6))");
            sql.executeUpdate(
                    "INSERT INTO conversation (id, public_id, user_id, agent_id, title, created_at, updated_at)"
                            + " VALUES (943, X'00000000000000000000000000000943', 941, 942, '정책', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
            sql.executeUpdate(
                    "INSERT INTO proactive_check (id, user_id, agent_id, conversation_id, trigger_type, status, started_at)"
                            + " VALUES (944, 941, 942, 943, 'MANUAL', 'SUCCEEDED', CURRENT_TIMESTAMP(6)),"
                            + " (945, 941, 942, 943, 'AUTONOMY', 'RUNNING', CURRENT_TIMESTAMP(6))");
            sql.executeUpdate(
                    "INSERT INTO proactive_value_evaluation (id, check_id, user_id, outcome, evidence_json, created_at)"
                            + " VALUES (946, 944, 941, 'EVALUATED', '{\"state\":{\"version\":1}}', CURRENT_TIMESTAMP(6))");
            sql.executeUpdate(DECISION + "(947, 941, 946, 1, 944, 'EXECUTE', '[\"READ_ONLY_SAFE\"]', '{}', 1,"
                    + " 'check:944', 'STARTED', 945, CURRENT_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                    + " VALUES (949, 'consent@example.com', '사용자B', 1, 'MEMBER', CURRENT_TIMESTAMP(6))");
            sql.executeUpdate("INSERT INTO user_autonomy_preference (user_id, read_only_execution, updated_at)"
                    + " VALUES (949, TRUE, CURRENT_TIMESTAMP(6))");

            assertThatThrownBy(() -> sql.executeUpdate(DECISION
                            + "(948, 941, 946, 1, 944, 'EXECUTE', '[]', '{}', 1, 'check:944', 'PENDING', NULL,"
                            + " CURRENT_TIMESTAMP(6))"))
                    .isInstanceOf(SQLException.class);

            sql.executeUpdate("DELETE FROM proactive_check WHERE id = 945");
            try (ResultSet rows = sql.executeQuery(
                    "SELECT execution_check_id, reasons_json FROM proactive_autonomy_decision WHERE id = 947")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1)).isNull();
                assertThat(rows.getString(2)).contains("READ_ONLY_SAFE");
            }
            sql.executeUpdate("DELETE FROM proactive_value_evaluation WHERE id = 946");
            try (ResultSet count = sql.executeQuery("SELECT COUNT(*) FROM proactive_autonomy_decision")) {
                assertThat(count.next()).isTrue();
                assertThat(count.getInt(1)).isZero();
            }
            sql.executeUpdate("DELETE FROM app_user WHERE id = 949");
            try (ResultSet count = sql.executeQuery("SELECT COUNT(*) FROM user_autonomy_preference")) {
                assertThat(count.next()).isTrue();
                assertThat(count.getInt(1)).isZero();
            }
        }
    }
}
