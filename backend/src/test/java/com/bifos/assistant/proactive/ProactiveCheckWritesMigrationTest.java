package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「먼저 살펴보기에 쓰기 도구 허용」 의 두 칸이 마이그레이션으로 생기고 기본값이 거짓인지 본다(ADR-082).
 *
 * <p>칸을 더하기 전 스키마에 에이전트와 살펴보기 줄을 넣어 두고, 이미 있던 줄과 칸을 적지 않고 새로 넣은 줄이 모두 거짓인지 본다.
 * 마이그레이션 번호는 머지 직전에 바뀔 수 있어 설명으로 그 마이그레이션을 찾는다. 모든 값은 합성이다.
 */
class ProactiveCheckWritesMigrationTest {
    private static final String MIGRATION_DESCRIPTION = "proactive check writes";
    private static final int USER = 911;
    private static final int AGENT = 912;
    private static final int OTHER_AGENT = 913;
    private static final int CONVERSATION = 914;

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:proactive-check-writes-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        flyway().target(versionBeforeWrites()).load().migrate();
        execute("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)" + " VALUES (" + USER
                + ", 'u@example.com', 'u', 1, 'MEMBER', CURRENT_TIMESTAMP(6))");
        execute(agentRow(AGENT, "a"));
        execute("INSERT INTO conversation (id, public_id, user_id, agent_id, title, created_at, updated_at)"
                + " VALUES (" + CONVERSATION + ", X'00000000000000000000000000000002', " + USER + ", " + AGENT
                + ", 'before', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        execute(checkRow());
        flyway().load().migrate();
    }

    @Test
    @DisplayName("이미 있던 에이전트와 살펴보기 줄의 쓰기 허용 칸은 거짓이다")
    void fillsExistingRowsWithFalse() throws SQLException {
        assertThat(queryBoolean("SELECT proactive_check_writes_allowed FROM agent WHERE id = " + AGENT))
                .as("이미 있던 에이전트")
                .isFalse();
        assertThat(queryBoolean("SELECT writes_allowed FROM proactive_check WHERE agent_id = " + AGENT))
                .as("이미 있던 살펴보기")
                .isFalse();
    }

    @Test
    @DisplayName("칸을 적지 않고 새로 넣은 줄도 거짓이다")
    void defaultsNewRowsToFalse() throws SQLException {
        execute(agentRow(OTHER_AGENT, "b"));
        execute("DELETE FROM proactive_check");
        execute(checkRow());

        assertThat(queryBoolean("SELECT proactive_check_writes_allowed FROM agent WHERE id = " + OTHER_AGENT))
                .as("새 에이전트")
                .isFalse();
        assertThat(queryBoolean("SELECT writes_allowed FROM proactive_check"))
                .as("새 살펴보기")
                .isFalse();
    }

    private FluentConfiguration flyway() {
        return Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration");
    }

    /** 쓰기 허용 마이그레이션 바로 앞의 버전. 번호를 적지 않고 설명으로 찾는다. */
    private String versionBeforeWrites() {
        List<MigrationInfo> all = Arrays.asList(flyway().load().info().all());
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (MIGRATION_DESCRIPTION.equals(all.get(i).getDescription())) {
                index = i;
            }
        }
        assertThat(index).as("설명이 '%s' 인 마이그레이션의 자리", MIGRATION_DESCRIPTION).isPositive();
        return all.get(index - 1).getVersion().getVersion();
    }

    private static String agentRow(int id, String code) {
        return "INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,"
                + " credential_scope, visibility, owner_user_id, enabled, created_at)"
                + " VALUES (" + id + ", '" + code + "', 'n', '" + code + "', 'http://localhost', 'SUBSCRIPTION',"
                + " 'SHARED_HOUSEHOLD', 'PRIVATE', " + USER + ", TRUE, CURRENT_TIMESTAMP(6))";
    }

    private static String checkRow() {
        return "INSERT INTO proactive_check (user_id, agent_id, conversation_id, trigger_type, status, started_at)"
                + " VALUES (" + USER + ", " + AGENT + ", " + CONVERSATION
                + ", 'MANUAL', 'RUNNING', CURRENT_TIMESTAMP(6))";
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private boolean queryBoolean(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).as("줄: %s", sql).isTrue();
            return row.getBoolean(1);
        }
    }
}
