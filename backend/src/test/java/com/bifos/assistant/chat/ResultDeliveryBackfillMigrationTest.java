package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 결과 전달 항목과 마지막 시도를 옛 표에서 {@code result_delivery} 의 칸으로 옮기는 마이그레이션을 본다
 * (ADR-20261102 / table-reduce-r2).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로 운영과 같은 Flyway 스키마는 여기서 따로 만든다. 옮긴 {@code items_json} 은
 * {@code ResultDeliveryRecordTest} 가 Control Plane 이 쓴 값으로 확인하는 버전 1 모양과 같은 글자인지 본다.
 */
class ResultDeliveryBackfillMigrationTest {

    /** 옮기는 마이그레이션 바로 앞 버전이다. */
    private static final String BEFORE = "20261102074304";


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:result-delivery-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("항목은 넣은 순서의 JSON 으로, 시도는 번호가 가장 큰 시도만 칸으로 옮기고 옛 표는 남긴다")
    void movesItemsAndLastAttemptIntoDeliveryRow() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO result_delivery (id, conversation_id, status, attempt_count, created_at, updated_at) VALUES
                        (1, 10, 'DELIVERED', 2, '2026-11-01 00:00:00', '2026-11-01 00:05:00'),
                        (2, 10, 'DELIVERING', 1, '2026-11-01 01:00:00', '2026-11-01 01:00:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO result_delivery_item (id, delivery_id, source, result_key) VALUES
                        (5, 1, 'DELEGATION', '42'),
                        (6, 1, 'CONNECTOR_ACTION', 'a1b2'),
                        (7, 2, 'DELEGATION', '43')
                    """);
            statement.executeUpdate("""
                    INSERT INTO result_delivery_attempt
                        (id, delivery_id, attempt_no, status, execution_id, notice_message_id, error_code, started_at, finished_at)
                    VALUES
                        (1, 1, 1, 'FAILED', 100, 200, 'HERMES_RUN_FAILED', '2026-11-01 00:00:00', '2026-11-01 00:01:00'),
                        (2, 1, 2, 'SUCCEEDED', 101, 201, NULL, '2026-11-01 00:04:00.123456', '2026-11-01 00:05:00'),
                        (3, 2, 1, 'RUNNING', NULL, 202, NULL, '2026-11-01 01:00:00', NULL)
                    """);
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT items_json FROM result_delivery ORDER BY id"))
                .containsExactly(
                    "{\"version\":1,\"items\":[{\"source\":\"DELEGATION\",\"resultKey\":\"42\"},"
                        + "{\"source\":\"CONNECTOR_ACTION\",\"resultKey\":\"a1b2\"}]}",
                    "{\"version\":1,\"items\":[{\"source\":\"DELEGATION\",\"resultKey\":\"43\"}]}");
            assertThat(rows(statement, """
                    SELECT last_attempt_status, last_attempt_execution_id, last_attempt_notice_message_id, last_attempt_error_code
                      FROM result_delivery ORDER BY id
                    """))
                .as("마지막 시도만 옮긴다")
                .containsExactly(
                    List.of("SUCCEEDED", "101", "201", "-"),
                    List.of("RUNNING", "-", "202", "-"));
            assertThat(strings(statement, """
                    SELECT COUNT(*) FROM result_delivery d
                      JOIN result_delivery_attempt a ON a.delivery_id = d.id AND a.attempt_no = d.attempt_count
                     WHERE d.last_attempt_started_at = a.started_at
                       AND (d.last_attempt_finished_at = a.finished_at
                            OR (d.last_attempt_finished_at IS NULL AND a.finished_at IS NULL))
                    """))
                .as("시작과 끝 시각을 마이크로초까지 그대로 옮긴다")
                .containsExactly("2");
            assertThat(strings(statement, "SELECT COUNT(*) FROM result_delivery_item"))
                .as("옛 표는 직전 이미지로 되돌릴 수 있게 지우지 않는다")
                .containsExactly("3");
            assertThat(strings(statement, "SELECT COUNT(*) FROM result_delivery_attempt")).containsExactly("3");
        }
    }


    @Test
    @DisplayName("전달 기록이 없으면 아무것도 바꾸지 않는다")
    void changesNothingWithoutDeliveries() throws SQLException {
        Database database = createDatabase();
        migrate(database, null);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT COUNT(*) FROM result_delivery")).containsExactly("0");
        }
    }


    @Test
    @DisplayName("마지막 시도의 번호가 묶음의 시도 수와 다르면 옮기지 않고 멈춘다")
    void refusesWhenLastAttemptDoesNotMatchAttemptCount() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO result_delivery (id, conversation_id, status, attempt_count, created_at, updated_at)
                    VALUES (1, 10, 'FAILED', 3, '2026-11-01 00:00:00', '2026-11-01 00:00:00')
                    """);
            statement.executeUpdate(
                "INSERT INTO result_delivery_item (id, delivery_id, source, result_key) VALUES (1, 1, 'DELEGATION', '42')");
            statement.executeUpdate("""
                    INSERT INTO result_delivery_attempt (id, delivery_id, attempt_no, status, started_at, finished_at)
                    VALUES (1, 1, 2, 'FAILED', '2026-11-01 00:00:00', '2026-11-01 00:00:01')
                    """);
        }

        assertThatThrownBy(() -> migrate(database, null))
            .isInstanceOf(RuntimeException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT COALESCE(items_json, '-') FROM result_delivery"))
                .as("항목도 옮기지 않는다")
                .containsExactly("-");
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


    private static List<String> strings(Statement statement, String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }


    /** 줄마다 칸의 값을 문자열로 읽는다. NULL 은 {@code -} 다. */
    private static List<List<String>> rows(Statement statement, String query) throws SQLException {
        List<List<String>> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(query)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    String value = rows.getString(column);
                    row.add(value == null ? "-" : value);
                }
                values.add(row);
            }
        }
        return values;
    }


    private static Connection connect(Database database) throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
