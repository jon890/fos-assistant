package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 문제 후보 표가 마이그레이션으로 생기고, 살펴보기 줄을 지우면 그 후보도 지워지는지 본다(ADR-092).
 *
 * <p>마이그레이션 번호는 머지 직전에 바뀔 수 있어 설명으로 그 마이그레이션이 있는지 확인한다. 모든 값은 합성이다.
 */
class ProactiveCheckProblemMigrationTest {
    private static final String MIGRATION_DESCRIPTION = "proactive check problem";
    private static final int USER = 921;
    private static final int AGENT = 922;
    private static final int CONVERSATION = 923;
    private static final int CHECK = 924;

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:proactive-check-problem-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load();
        assertThat(Arrays.stream(flyway.info().all())
                        .anyMatch(info -> MIGRATION_DESCRIPTION.equals(info.getDescription())))
                .as("설명이 '%s' 인 마이그레이션", MIGRATION_DESCRIPTION)
                .isTrue();
        flyway.migrate();
        execute("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)" + " VALUES (" + USER
                + ", 'u@example.com', 'u', 1, 'MEMBER', CURRENT_TIMESTAMP(6))");
        execute("INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,"
                + " credential_scope, visibility, owner_user_id, enabled, created_at)"
                + " VALUES (" + AGENT + ", 'a', 'n', 'a', 'http://localhost', 'SUBSCRIPTION',"
                + " 'SHARED_HOUSEHOLD', 'PRIVATE', " + USER + ", TRUE, CURRENT_TIMESTAMP(6))");
        execute("INSERT INTO conversation (id, public_id, user_id, agent_id, title, created_at, updated_at)"
                + " VALUES (" + CONVERSATION + ", X'00000000000000000000000000000003', " + USER + ", " + AGENT
                + ", 'check', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        execute("INSERT INTO proactive_check (id, user_id, agent_id, conversation_id, trigger_type, status, started_at)"
                + " VALUES (" + CHECK + ", " + USER + ", " + AGENT + ", " + CONVERSATION
                + ", 'SCHEDULED', 'SUCCEEDED', CURRENT_TIMESTAMP(6))");
    }

    @Test
    @DisplayName("받아들인 후보와 근거 참조를 저장하고, 살펴보기 줄을 지우면 함께 지워진다")
    void storesProblemAndCascadesWithCheck() throws SQLException {
        execute("INSERT INTO proactive_check_problem (check_id, conversation_id, status, problem_key, problem,"
                + " related_goal, action_type, action_text, confidence, expected_benefit, side_effect,"
                + " evidence_json, evidence_checked_at, created_at) VALUES (" + CHECK + ", " + CONVERSATION
                + ", 'ACCEPTED', 'position:deadline:example-corp', '마감 전에 정해야 한다', '이직 준비', 'QUESTION',"
                + " '지원할까요', 'HIGH', '기회를 놓치지 않는다', 'NONE',"
                + " '[{\"topicKey\":\"position:example\",\"sourceUrl\":\"https://jobs.example.com/a\"}]',"
                + " CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        execute("INSERT INTO proactive_check_problem (check_id, conversation_id, status, drop_reason, problem_key,"
                + " evidence_json, created_at) VALUES (" + CHECK + ", " + CONVERSATION
                + ", 'DROPPED', 'NO_GOAL', '', '[]', CURRENT_TIMESTAMP(6))");
        assertThat(count("SELECT COUNT(*) FROM proactive_check_problem WHERE conversation_id = " + CONVERSATION))
                .isEqualTo(2);

        execute("DELETE FROM proactive_check WHERE id = " + CHECK);

        assertThat(count("SELECT COUNT(*) FROM proactive_check_problem")).isZero();
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).as("줄: %s", sql).isTrue();
            return row.getLong(1);
        }
    }
}
