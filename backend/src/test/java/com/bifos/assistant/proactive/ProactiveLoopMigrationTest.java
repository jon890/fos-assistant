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

class ProactiveLoopMigrationTest {

    @Test
    @DisplayName("마이그레이션으로 설정은 사용자와 에이전트마다, 시도는 원천 살펴보기마다 하나만 저장되고 평가를 지우면 평가 번호만 비운다")
    void enforcesUniqueKeysAndClearsEvaluation() throws Exception {
        String url = "jdbc:h2:mem:proactive-loop-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
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
                            + " VALUES (943, X'00000000000000000000000000000943', 941, 942, '점검', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
            sql.executeUpdate(
                    "INSERT INTO proactive_check (id, user_id, agent_id, conversation_id, trigger_type, status, started_at)"
                            + " VALUES (944, 941, 942, 943, 'SCHEDULED', 'SUCCEEDED', CURRENT_TIMESTAMP(6))");
            sql.executeUpdate(
                    "INSERT INTO proactive_value_evaluation (id, check_id, user_id, outcome, evidence_json, created_at)"
                            + " VALUES (945, 944, 941, 'EMPTY', '{\"state\":{\"version\":1}}', CURRENT_TIMESTAMP(6))");

            sql.executeUpdate(
                    "INSERT INTO proactive_loop_setting (user_id, agent_id, enabled, snoozed_until, updated_at)"
                            + " VALUES (941, 942, TRUE, NULL, CURRENT_TIMESTAMP(6))");
            assertThatThrownBy(() -> sql.executeUpdate(
                            "INSERT INTO proactive_loop_setting (user_id, agent_id, enabled, snoozed_until, updated_at)"
                                    + " VALUES (941, 942, FALSE, NULL, CURRENT_TIMESTAMP(6))"))
                    .as("같은 사용자와 에이전트의 두 번째 설정 줄")
                    .isInstanceOf(SQLException.class);

            sql.executeUpdate(
                    "INSERT INTO proactive_loop_run (user_id, source_check_id, status, evaluation_id, created_at, finished_at)"
                            + " VALUES (941, 944, 'DECIDED', 945, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
            assertThatThrownBy(() -> sql.executeUpdate(
                            "INSERT INTO proactive_loop_run (user_id, source_check_id, status, created_at)"
                                    + " VALUES (941, 944, 'RUNNING', CURRENT_TIMESTAMP(6))"))
                    .as("같은 원천 살펴보기의 두 번째 시도 줄")
                    .isInstanceOf(SQLException.class);

            sql.executeUpdate("DELETE FROM proactive_value_evaluation WHERE id = 945");
            try (ResultSet rows = sql.executeQuery(
                    "SELECT status, evaluation_id FROM proactive_loop_run WHERE source_check_id = 944")) {
                assertThat(rows.next()).as("평가를 지운 뒤에도 시도 줄이 남는다").isTrue();
                assertThat(rows.getString(1)).isEqualTo("DECIDED");
                assertThat(rows.getObject(2)).as("지운 평가의 번호").isNull();
            }
            try (ResultSet count = sql.executeQuery("SELECT COUNT(*) FROM proactive_loop_setting")) {
                assertThat(count.next()).isTrue();
                assertThat(count.getInt(1)).as("유일 제약에 걸린 뒤 남은 설정 줄 수").isEqualTo(1);
            }
        }
    }
}
