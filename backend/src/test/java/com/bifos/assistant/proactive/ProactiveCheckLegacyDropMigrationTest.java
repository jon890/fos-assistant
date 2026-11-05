package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 먼저 살펴보기의 발견·문제 후보 표와 쓰지 않는 칸을 지우는 마이그레이션을 본다({@code docs/features/proactive/design.md}).
 * 옛 자동 실행(AUTONOMY) 줄을 먼저 지우고, 표와 칸이 사라진 뒤에도 남겨야 할 줄이 그대로인지 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들어 옛 표와 칸이 없으므로 운영과 같은 Flyway 스키마를 여기서 따로 만든다. 모든 값은 합성이다.
 */
class ProactiveCheckLegacyDropMigrationTest {

    /** 옛 줄을 지우고 표를 지우는 마이그레이션 바로 앞 버전이다. 이 버전까지는 옛 표와 칸이 남아 있다. */
    private static final String BEFORE = "20261105040320";

    /** 지울 칸이다. */
    private static final List<String> DROPPED_CHECK_COLUMNS = List.of(
        "new_findings", "reference_findings", "tree_input_tokens", "tree_cached_input_tokens", "tree_output_tokens");


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:proactive-drop-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("AUTONOMY 줄만 지우고 그 발견과 문제 후보도 함께 지운 뒤 표와 칸을 지운다")
    void deletesAutonomyChecksThenDropsTablesAndColumns() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            withoutForeignKeys(connection, () -> {
                insertCheck(statement, 1, "MANUAL");
                insertCheck(statement, 2, "SCHEDULED");
                insertCheck(statement, 3, "AUTONOMY");
                insertFinding(statement, 1);
                insertFinding(statement, 3);
                insertProblem(statement, 3);
                insertEvent(statement, 1, 1);
                insertEvent(statement, 2, 3);
            });
            assertThat(count(statement, "SELECT COUNT(*) FROM proactive_check_finding")).isEqualTo(2);
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT COUNT(*) FROM proactive_check WHERE trigger_type = 'AUTONOMY'"))
                .as("옛 자동 실행 줄").isZero();
            assertThat(count(statement, "SELECT COUNT(*) FROM proactive_check WHERE id IN (1, 2) AND tool_calls = 4 AND delegations = 2"))
                .as("나머지 살펴보기 줄과 남기는 칸").isEqualTo(2);
            assertThat(exists(statement, "proactive_check_finding")).as("발견 표").isFalse();
            assertThat(exists(statement, "proactive_check_problem")).as("문제 후보 표").isFalse();
            DROPPED_CHECK_COLUMNS.forEach(column -> assertThat(columnExists(statement, "proactive_check", column))
                .as("proactive_check.%s", column).isFalse());
            assertThat(columnExists(statement, "decision_feedback_event", "source_check_id"))
                .as("decision_feedback_event.source_check_id").isFalse();
            assertThat(count(statement, "SELECT COUNT(*) FROM decision_feedback_event")).as("반응 기록 줄은 그대로").isEqualTo(2);
        }
    }


    @Test
    @DisplayName("옮길 줄이 없어도 표와 칸을 지운다")
    void dropsWhenNothingToDelete() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(exists(statement, "proactive_check_finding")).isFalse();
            assertThat(exists(statement, "proactive_check_problem")).isFalse();
            assertThat(columnExists(statement, "proactive_check", "new_findings")).isFalse();
        }
    }


    private static void migrate(Database database, String target) {
        var configuration = Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    private static boolean mysql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
    }


    /** 사용자, 에이전트, 대화 줄 없이 줄을 넣으려고 그 연결의 외래 키 검사를 잠시 끈다. */
    private static void withoutForeignKeys(Connection connection, SqlWork work) throws SQLException {
        boolean mysql = mysql(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute(mysql ? "SET FOREIGN_KEY_CHECKS = 0" : "SET REFERENTIAL_INTEGRITY FALSE");
            work.run();
            statement.execute(mysql ? "SET FOREIGN_KEY_CHECKS = 1" : "SET REFERENTIAL_INTEGRITY TRUE");
        }
    }


    private static void insertCheck(Statement statement, long id, String trigger) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO proactive_check (id, user_id, agent_id, conversation_id, trigger_type, status, tool_calls,
                    delegations, new_findings, reference_findings, tree_input_tokens, started_at)
                VALUES (%d, 1, 1, 1, '%s', 'SUCCEEDED', 4, 2, 3, 1, 100, '2026-10-01 00:00:00')
                """.formatted(id, trigger));
    }


    private static void insertFinding(Statement statement, long checkId) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO proactive_check_finding (check_id, conversation_id, kind, area, title, created_at)
                VALUES (%d, 1, 'NEW', 'trend', '합성 발견', '2026-10-01 00:01:00')
                """.formatted(checkId));
    }


    private static void insertProblem(Statement statement, long checkId) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO proactive_check_problem (check_id, conversation_id, status, problem_key, evidence_json,
                    created_at)
                VALUES (%d, 1, 'ACCEPTED', 'trend:problem', '[]', '2026-10-01 00:01:00')
                """.formatted(checkId));
    }


    private static void insertEvent(Statement statement, long id, long checkId) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO decision_feedback_event (id, user_id, subject_type, subject_key, event_type, actor,
                    source_check_id, contract_version, occurred_at)
                VALUES (%d, 1, 'CHECK', 'proactive_check:%d', 'SURFACED', 'USER', %d, 1, '2026-10-01 00:02:00')
                """.formatted(id, checkId, checkId));
    }


    /** 표를 읽어 보고 오류가 나면 없는 표로 본다. 정보 스키마의 이름 대소문자가 DB 마다 달라 쓰지 않는다. */
    private static boolean exists(Statement statement, String table) {
        try (ResultSet rows = statement.executeQuery("SELECT 1 FROM " + table + " WHERE 1 = 0")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }


    private static boolean columnExists(Statement statement, String table, String column) {
        try (ResultSet rows = statement.executeQuery("SELECT " + column + " FROM " + table + " WHERE 1 = 0")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }


    private static long count(Statement statement, String query) throws SQLException {
        try (ResultSet rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }


    private static Connection connect(Database database) throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    /** 외래 키 검사를 끈 채 돌리는 SQL 묶음이다. */
    @FunctionalInterface
    interface SqlWork {
        void run() throws SQLException;
    }

    record Database(String url, String username, String password) {
    }
}
