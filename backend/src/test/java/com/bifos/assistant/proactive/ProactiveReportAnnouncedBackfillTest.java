package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.proactive.check.domain.CheckReport;
import com.bifos.assistant.proactive.check.domain.CheckReportJsonConverter;
import com.bifos.assistant.proactive.check.domain.ReportedFinding;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이미 남긴 살펴보기 보고에 되풀이 판정 재료를 채우는 마이그레이션을 본다({@code docs/features/proactive/design.md}).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로 운영과 같은 Flyway 스키마는 여기서 따로 만든다. 채운 JSON 은 Control Plane 이 쓰는
 * {@link CheckReportJsonConverter} 로 읽어 두 쪽의 모양이 같은지도 함께 본다. 모든 값은 합성이다.
 */
class ProactiveReportAnnouncedBackfillTest {

    /** 채우는 마이그레이션 바로 앞 버전이다. */
    private static final String BEFORE = "20261103054423";

    private static final String REPORT = """
            {"changed":["합성 변화"],"done":[],"evidence":["https://example.com/a"],"needsApproval":[],"next":[]}""";


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:proactive-report-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("보고가 있는 살펴보기에 주제 키 있는 새로 알릴 발견과 받아들인 문제 키를 채우고 다섯 칸은 그대로 둔다")
    void fillsAnnouncedFindingsAndAcceptedProblemKeys() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            withoutForeignKeys(connection, () -> {
                insertCheck(connection, 1, REPORT);
                insertCheck(connection, 2, null);
                insertFinding(statement, 1, "NEW", "'trend:a'", "알린 소식", "'https://example.com/a'");
                insertFinding(statement, 1, "NEW", "NULL", "주제 키 없는 소식", "'https://example.com/b'");
                insertFinding(statement, 1, "NEW", "'  '", "빈 주제 키", "'https://example.com/c'");
                insertFinding(statement, 1, "REFERENCE", "'trend:ref'", "참고 소식", "NULL");
                insertFinding(statement, 2, "NEW", "'trend:silent'", "보고 없는 살펴보기의 발견", "'https://example.com/d'");
                insertProblem(statement, 1, "ACCEPTED", "trend:accepted");
                insertProblem(statement, 1, "ACCEPTED", "trend:accepted");
                insertProblem(statement, 1, "DROPPED", "trend:dropped");
            });
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            CheckReport report = reportOf(statement, 1);
            assertThat(report.changed()).containsExactly("합성 변화");
            assertThat(report.evidence()).containsExactly("https://example.com/a");
            assertThat(report.announced())
                .containsExactly(new ReportedFinding("trend:a", "https://example.com/a", "알린 소식"));
            assertThat(report.acceptedProblemKeys()).containsExactly("trend:accepted");
            assertThat(reportOf(statement, 2)).as("보고 없는 살펴보기는 그대로").isNull();
            assertThat(count(statement, "SELECT COUNT(*) FROM proactive_check_finding")).as("옛 표는 지우지 않는다").isEqualTo(5);
        }
    }


    @Test
    @DisplayName("옮길 발견과 문제 키가 없는 보고는 두 칸을 빈 목록으로 채운다")
    void fillsEmptyListsWhenNothingToCarry() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database)) {
            withoutForeignKeys(connection, () -> insertCheck(connection, 1, REPORT));
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            String raw = rawReportOf(statement, 1);
            assertThat(raw).contains("\"announced\"").contains("\"acceptedProblemKeys\"");
            CheckReport report = reportOf(statement, 1);
            assertThat(report.announced()).isEmpty();
            assertThat(report.acceptedProblemKeys()).isEmpty();
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


    /** 사용자, 에이전트, 대화 줄 없이 살펴보기 줄을 넣으려고 그 연결의 외래 키 검사를 잠시 끈다. */
    private static void withoutForeignKeys(Connection connection, SqlWork work) throws SQLException {
        boolean mysql = mysql(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute(mysql ? "SET FOREIGN_KEY_CHECKS = 0" : "SET REFERENTIAL_INTEGRITY FALSE");
            work.run();
            statement.execute(mysql ? "SET FOREIGN_KEY_CHECKS = 1" : "SET REFERENTIAL_INTEGRITY TRUE");
        }
    }


    private static void insertCheck(Connection connection, long id, String reportJson) throws SQLException {
        String report = reportJson == null ? "NULL" : "'" + reportJson + "'" + (mysql(connection) ? "" : " FORMAT JSON");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO proactive_check (id, user_id, agent_id, conversation_id, trigger_type, status, outcome,
                        report_json, started_at, finished_at)
                    VALUES (%d, 1, 1, 1, 'MANUAL', 'SUCCEEDED', 'FINDINGS', %s,
                        '2026-10-01 00:00:00', '2026-10-01 00:01:00')
                    """.formatted(id, report));
        }
    }


    private static void insertFinding(
        Statement statement, long checkId, String kind, String topicKey, String title, String sourceUrl)
        throws SQLException {
        statement.executeUpdate("""
                INSERT INTO proactive_check_finding (check_id, conversation_id, kind, area, topic_key, title, source_url,
                    created_at)
                VALUES (%d, 1, '%s', 'trend', %s, '%s', %s, '2026-10-01 00:01:00')
                """.formatted(checkId, kind, topicKey, title, sourceUrl));
    }


    private static void insertProblem(Statement statement, long checkId, String status, String problemKey)
        throws SQLException {
        statement.executeUpdate("""
                INSERT INTO proactive_check_problem (check_id, conversation_id, status, problem_key, evidence_json,
                    created_at)
                VALUES (%d, 1, '%s', '%s', '[]', '2026-10-01 00:01:00')
                """.formatted(checkId, status, problemKey));
    }


    private static String rawReportOf(Statement statement, long checkId) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT report_json FROM proactive_check WHERE id = " + checkId)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }


    private static CheckReport reportOf(Statement statement, long checkId) throws SQLException {
        return new CheckReportJsonConverter().convertToEntityAttribute(rawReportOf(statement, checkId));
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
