package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 먼저 살펴보기의 표 둘과 점검 대화를 가리는 칸이 마이그레이션으로 생기는지 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다. 마이그레이션 번호는 머지 직전에 바뀔 수
 * 있어 시험에 적지 않고, 설명({@code proactive_check})으로 그 마이그레이션을 찾는다.
 */
class ProactiveCheckMigrationTest {
    private static final String MIGRATION_DESCRIPTION = "proactive check";
    private static final int USER = 901;
    private static final int AGENT = 902;
    private static final int CONVERSATION = 903;
    private static final int EXECUTION = 904;
    private static final int OTHER_EXECUTION = 905;

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:proactive-check-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        // 칸을 더하기 전 스키마에 대화를 넣어 두면, 이미 있던 대화가 어떤 purpose 로 채워지는지 본다.
        flyway().target(versionBeforeProactiveCheck()).load().migrate();
        execute("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                + " VALUES (" + USER + ", 'u@example.com', 'u', 1, 'MEMBER', CURRENT_TIMESTAMP(6))");
        execute("INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,"
                + " credential_scope, visibility, owner_user_id, enabled, created_at)"
                + " VALUES (" + AGENT + ", 'a', 'n', 'p', 'http://localhost', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD',"
                + " 'PRIVATE', " + USER + ", TRUE, CURRENT_TIMESTAMP(6))");
        execute("INSERT INTO conversation (id, public_id, user_id, agent_id, title, created_at, updated_at)"
                + " VALUES (" + CONVERSATION + ", X'00000000000000000000000000000001', " + USER + ", " + AGENT
                + ", 'before', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        execute("INSERT INTO agent_execution (id, user_id, conversation_id, profile_name, cost_mode, status,"
                + " started_at) VALUES"
                + " (" + EXECUTION + ", " + USER + ", " + CONVERSATION + ", 'p', 'SUBSCRIPTION', 'RUNNING',"
                + " CURRENT_TIMESTAMP(6)),"
                + " (" + OTHER_EXECUTION + ", " + USER + ", " + CONVERSATION + ", 'p', 'SUBSCRIPTION', 'RUNNING',"
                + " CURRENT_TIMESTAMP(6))");
        flyway().load().migrate();
    }

    @Test
    @DisplayName("Flyway 가 마지막 마이그레이션까지 적용한다")
    void appliesEveryMigrationUpToTheLast() {
        MigrationInfoService info = flyway().load().info();
        MigrationInfo[] all = info.all();

        assertThat(info.pending()).as("적용하지 않은 마이그레이션").isEmpty();
        assertThat(info.current().getVersion())
                .as("적용한 마지막 버전")
                .isEqualTo(all[all.length - 1].getVersion());
        assertThat(Arrays.stream(info.applied()).map(MigrationInfo::getDescription))
                .as("적용한 마이그레이션의 설명")
                .contains(MIGRATION_DESCRIPTION);
    }

    @Test
    @DisplayName("이미 있던 대화의 purpose 는 CHAT 이다")
    void fillsPurposeOfExistingConversationWithChat() throws SQLException {
        assertThat(queryString("SELECT purpose FROM conversation WHERE id = " + CONVERSATION))
                .isEqualTo("CHAT");
    }

    @Test
    @DisplayName("살펴보기 줄을 저장하고 같은 실행 줄을 가리키는 둘째 줄은 거절한다")
    void savesCheckRowAndRejectsSecondRowWithSameRootExecution() throws SQLException {
        execute(checkRow(String.valueOf(EXECUTION)));

        assertThat(queryString("SELECT status FROM proactive_check WHERE root_execution_id = " + EXECUTION))
                .isEqualTo("RUNNING");
        assertThatThrownBy(() -> execute(checkRow(String.valueOf(EXECUTION))))
                .as("root_execution_id 가 같은 둘째 줄")
                .isInstanceOf(SQLException.class);
        execute(checkRow(String.valueOf(OTHER_EXECUTION)));
        assertThat(countChecks()).as("실행 줄이 다른 둘째 줄까지 저장한 수").isEqualTo(2);
    }

    @Test
    @DisplayName("실행 줄을 만들기 전에 실패한 살펴보기는 여럿이어도 저장한다")
    void savesSeveralCheckRowsWithoutRootExecution() throws SQLException {
        execute(checkRow("NULL"));
        execute(checkRow("NULL"));

        assertThat(countChecks()).isEqualTo(2);
    }

    @Test
    @DisplayName("살펴보기 줄을 지우면 그 발견도 함께 지운다")
    void deletesFindingsWithTheirCheck() throws SQLException {
        execute(checkRow(String.valueOf(EXECUTION)));
        long checkId = queryLong("SELECT id FROM proactive_check WHERE root_execution_id = " + EXECUTION);
        execute("INSERT INTO proactive_check_finding (check_id, conversation_id, kind, area, topic_key, title,"
                + " created_at) VALUES (" + checkId + ", " + CONVERSATION
                + ", 'NEW', 'trend', 'topic-1', '제목', CURRENT_TIMESTAMP(6))");

        execute("DELETE FROM proactive_check WHERE id = " + checkId);

        assertThat(queryLong("SELECT COUNT(*) FROM proactive_check_finding")).isZero();
    }

    private FluentConfiguration flyway() {
        return Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration");
    }

    /** 살펴보기 마이그레이션 바로 앞의 버전. 번호를 적지 않고 설명으로 찾는다. */
    private String versionBeforeProactiveCheck() {
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

    private static String checkRow(String rootExecutionId) {
        return "INSERT INTO proactive_check (user_id, agent_id, conversation_id, root_execution_id, trigger_type,"
                + " status, started_at) VALUES (" + USER + ", " + AGENT + ", " + CONVERSATION + ", " + rootExecutionId
                + ", 'MANUAL', 'RUNNING', CURRENT_TIMESTAMP(6))";
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private long countChecks() throws SQLException {
        return queryLong("SELECT COUNT(*) FROM proactive_check");
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).as("줄: %s", sql).isTrue();
            return row.getLong(1);
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).as("줄: %s", sql).isTrue();
            return row.getString(1);
        }
    }
}
