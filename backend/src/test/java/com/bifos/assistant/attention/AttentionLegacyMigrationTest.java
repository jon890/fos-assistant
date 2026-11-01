package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 옛 {@code attention_control}, {@code attention_event} 의 값을 반응 기록으로 복사하는 마이그레이션을 본다. 옛 표를 지우지 않고, 숨긴
 * 항목과 미룬 기한이 그대로 옮겨지는지, 노출과 열기만 옮기는지를 확인한다.
 */
class AttentionLegacyMigrationTest {

    private static final String MIGRATION = "20261031171513";
    private static final String HIDDEN_AT = "2026-09-01 03:00:00";
    private static final String UNTIL = "2026-11-02 00:00:00";

    private Database database;


    @BeforeEach
    void setUp() throws SQLException {
        database = createDatabase();
        migrateBeforeCopy();
        seedLegacy();
        migrateAll();
    }


    /** 실제 MySQL 검사가 같은 마이그레이션과 줄을 쓴다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:attention-legacy-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("숨긴 줄은 같은 상태 지문과 카드로 DISMISSED 제어 사건이 된다")
    void copiesHideAsDismissedControl() throws SQLException {
        assertThat(rows("SELECT subject_key, subject_version, card_key, reason_code, actor, subject_type,"
            + " CASE WHEN occurred_at = TIMESTAMP '" + HIDDEN_AT + "' THEN 'same' ELSE 'different' END"
            + " FROM decision_feedback_event WHERE event_type = 'DISMISSED' AND card_key IS NOT NULL"))
                .containsExactly(List.of(
                    "conversation:hidden", "state-hidden", "FAILURES", "ATTENTION_HIDE", "USER", "ATTENTION_ITEM", "same"));
    }


    @Test
    @DisplayName("미룬 줄은 같은 기한의 POSTPONED 제어 사건이 된다")
    void copiesSnoozeWithSameUntil() throws SQLException {
        assertThat(rows("SELECT subject_key, card_key,"
            + " CASE WHEN until_at = TIMESTAMP '" + UNTIL + "' THEN 'same' ELSE 'different' END,"
            + " subject_version, reason_code FROM decision_feedback_event WHERE event_type = 'POSTPONED'"))
                .containsExactly(Arrays.asList("conversation:snoozed", "CONTINUE", "same", null, "ATTENTION_SNOOZE"));
    }


    @Test
    @DisplayName("노출, 열기, 동작만 옮기고 판정은 옮기지 않으며 계기만 reason_code 에 둔다")
    void copiesOnlyViewsWithoutLevel() throws SQLException {
        assertThat(rows("SELECT event_type, subject_key, subject_version, reason_code, actor FROM decision_feedback_event"
            + " WHERE subject_type = 'ATTENTION_ITEM' AND card_key IS NULL ORDER BY event_type"))
                .containsExactly(
                    List.of("ACTED", "conversation:viewed", "state-viewed", "EXECUTION_FAILED", "USER"),
                    List.of("OPENED", "conversation:viewed", "state-viewed", "EXECUTION_FAILED", "USER"),
                    List.of("SHOWN", "conversation:viewed", "state-viewed", "EXECUTION_FAILED", "SYSTEM"));
    }


    @Test
    @DisplayName("옮긴 줄마다 옛 번호로 만든 event_key 를 달고, 옛 표는 그대로 남는다")
    void marksLegacyKeysAndKeepsOldTables() throws SQLException {
        assertThat(rows("SELECT COUNT(*) FROM decision_feedback_event WHERE event_key LIKE 'legacy:attention_%'"))
            .containsExactly(List.of("5"));
        assertThat(rows("SELECT COUNT(*) FROM attention_control")).containsExactly(List.of("2"));
        assertThat(rows("SELECT COUNT(*) FROM attention_event")).containsExactly(List.of("5"));
    }


    private void migrateBeforeCopy() {
        Flyway info = Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .load();
        String target = Arrays.stream(info.info().all())
            .map(MigrationInfo::getVersion)
            .filter(version -> version != null && version.compareTo(MigrationVersion.fromVersion(MIGRATION)) < 0)
            .max(MigrationVersion::compareTo)
            .orElseThrow()
            .getVersion();
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .target(target)
            .load()
            .migrate();
    }


    private void migrateAll() {
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }


    private void seedLegacy() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                + " VALUES (9001, 'attention-legacy@example.com', '아빠', 1, 'MEMBER', CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO attention_control"
                + " (user_id, card_key, item_key, action, state_key, until_at, created_at, updated_at) VALUES"
                + " (9001, 'FAILURES', 'conversation:hidden', 'HIDE', 'state-hidden', NULL, '2026-08-31 03:00:00', '"
                + HIDDEN_AT + "'),"
                + " (9001, 'CONTINUE', 'conversation:snoozed', 'SNOOZE', NULL, '" + UNTIL
                + "', '2026-10-30 03:00:00', '2026-10-30 03:00:00')");
            statement.executeUpdate("INSERT INTO attention_event"
                + " (user_id, item_key, state_key, trigger_type, attention, event_type, stale, created_at) VALUES"
                + " (9001, 'conversation:viewed', 'state-viewed', 'EXECUTION_FAILED', 'NOW', 'SHOWN', FALSE, '2026-10-01 00:00:00'),"
                + " (9001, 'conversation:viewed', 'state-viewed', 'EXECUTION_FAILED', 'NOW', 'OPENED', FALSE, '2026-10-01 00:00:10'),"
                + " (9001, 'conversation:viewed', 'state-viewed', 'EXECUTION_FAILED', 'NOW', 'ACTED', FALSE, '2026-10-01 00:00:20'),"
                + " (9001, 'conversation:hidden', 'state-hidden', 'EXECUTION_FAILED', 'NOW', 'HIDDEN', FALSE, '2026-10-01 00:01:00'),"
                + " (9001, 'conversation:snoozed', 'state-snoozed', 'EXECUTION_FAILED', 'LATER', 'SNOOZED', FALSE, '2026-10-01 00:02:00')");
        }
    }


    /** 질의 결과를 줄마다 열의 글 값 목록으로 읽는다. */
    private List<List<String>> rows(String sql) throws SQLException {
        try (Connection connection = connect();
            Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery(sql)) {
            List<List<String>> rows = new ArrayList<>();
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    row.add(result.getString(column));
                }
                rows.add(row);
            }
            return rows;
        }
    }


    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
