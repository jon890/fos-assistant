package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftAsset;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftAssetsConverter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 원고 저장 job 을 snapshot 칸으로, 사진 참조를 revision 의 {@code assets_json} 으로 옮기는 마이그레이션을 본다
 * (ADR-20261102 / table-reduce-r2 의 원고 D1).
 *
 * <p>칸을 더하는 DDL 까지 적용한 뒤 옛 테이블에 줄을 넣고 나머지를 적용한다. 옮긴 JSON 은 엔티티가 쓰는
 * {@link ContentDraftAssetsConverter} 로 읽어 두 쪽의 모양이 같은지도 함께 본다.
 */
class ContentDraftJobAssetMigrationTest {

    /** 칸을 더하는 DDL 이다. 이 버전까지 적용하면 옮기는 Java 마이그레이션만 남는다. */
    private static final String COLUMNS = "20261102074316";

    /** 처음 옮기는 Java 마이그레이션이다. 이 버전까지는 옛 테이블이 남아 있다. */
    private static final String MOVED = "20261102074317";

    private static final String FINGERPRINT = "a".repeat(64);

    private static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database("jdbc:h2:mem:content-draft-job-asset-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("job 칸은 그 snapshot 줄로, 사진 참조는 position 순서의 JSON 배열로 옮겨지고 옛 테이블은 남는다")
    void movesJobsAndAssetsWithoutDroppingOldTables() throws SQLException {
        Database database = createDatabase();
        migrate(database, COLUMNS);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            parents(statement);
            statement.executeUpdate(snapshot(1));
            statement.executeUpdate(snapshot(2));
            statement.executeUpdate("""
                INSERT INTO content_draft_job (id, snapshot_id, action_id, owner_user_id, conversation_id, job_id, status, created_at,
                    approved_by, approved_at, last_checked_at, start_claimed_at, save_claimed_at, stage, save_clicked, error_code,
                    error_reason)
                VALUES (7, 1, 31, 1, 1, '11111111-1111-4111-8111-111111111111', 'UNKNOWN', '2026-11-01 01:02:03.123456', 1,
                    '2026-11-01 01:03:00.000001', '2026-11-01 01:04:00', '2026-11-01 01:05:00', '2026-11-01 01:06:00', 'saving', TRUE,
                    'CONTENT_DRAFT_JOB_EXPIRED', 'title_cursor_covered')
                """);
            statement.executeUpdate("""
                INSERT INTO content_draft_asset (revision_id, position, attachment_id, source_fingerprint, observation_id, observation_revision)
                VALUES (1, 3, 1, '%s', 5, 2), (1, 0, 2, '%s', NULL, NULL)
                """.formatted(FINGERPRINT, FINGERPRINT));
        }

        migrate(database, MOVED);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(row(statement, """
                SELECT action_id, job_id, job_status, job_created_at, approved_by, approved_at, last_checked_at, start_claimed_at,
                    save_claimed_at, job_stage, save_clicked, error_code, error_reason FROM content_draft_snapshot WHERE id = 1
                """)).containsExactly(
                "31", "11111111-1111-4111-8111-111111111111", "UNKNOWN", "2026-11-01 01:02:03.123456", "1",
                "2026-11-01 01:03:00.000001", "2026-11-01 01:04:00.000000", "2026-11-01 01:05:00.000000",
                "2026-11-01 01:06:00.000000", "saving",
                "true", "CONTENT_DRAFT_JOB_EXPIRED", "title_cursor_covered");
            assertThat(row(statement, "SELECT action_id, job_status, save_clicked FROM content_draft_snapshot WHERE id = 2"))
                .as("저장 요청 전 snapshot 은 job 칸이 비어 있다")
                .containsOnlyNulls().hasSize(3);
            assertThat(assets(statement, 1)).containsExactly(
                new ContentDraftAsset(0, 2L, FINGERPRINT, null, null),
                new ContentDraftAsset(3, 1L, FINGERPRINT, 5L, 2L));
            assertThat(assets(statement, 2)).as("사진이 없는 revision 은 빈 배열이다").isEmpty();
            assertThat(row(statement, "SELECT (SELECT COUNT(*) FROM content_draft_job) + (SELECT COUNT(*) FROM content_draft_asset)"))
                .as("처음 옮긴 단계는 직전 이미지로 되돌릴 수 있게 옛 테이블을 지우지 않는다")
                .containsExactly("3");
        }
    }


    @Test
    @DisplayName("job 칸이 이미 찬 snapshot 은 덮어쓰지 않고 옮긴 수가 맞지 않아 멈춘다")
    void refusesToOverwriteFilledSnapshot() throws SQLException {
        Database database = createDatabase();
        migrate(database, COLUMNS);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            parents(statement);
            statement.executeUpdate(snapshot(1));
            statement.executeUpdate("UPDATE content_draft_snapshot SET action_id = 99, job_status = 'SUCCEEDED' WHERE id = 1");
            statement.executeUpdate("""
                INSERT INTO content_draft_job (id, snapshot_id, action_id, owner_user_id, conversation_id, status, created_at)
                VALUES (7, 1, 31, 1, 1, 'PREVIEW_READY', '2026-11-01 01:02:03')
                """);
        }

        assertThatThrownBy(() -> migrate(database, MOVED)).isInstanceOf(RuntimeException.class);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(row(statement, "SELECT action_id, job_status FROM content_draft_snapshot WHERE id = 1"))
                .containsExactly("99", "SUCCEEDED");
        }
    }


    @Test
    @DisplayName("직전 이미지가 옛 테이블에만 쓴 job 과 사진 참조를 다시 옮기고 이미 찬 칸은 덮어쓰지 않은 뒤 옛 테이블을 지운다")
    void remigratesRowsWrittenByPreviousImageAndDropsOldTables() throws SQLException {
        Database database = createDatabase();
        migrate(database, COLUMNS);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            parents(statement);
            statement.executeUpdate(snapshot(1));
            statement.executeUpdate(snapshot(2));
            statement.executeUpdate(snapshot(3));
            statement.executeUpdate(job(7, 1, 31, "SUCCEEDED"));
            statement.executeUpdate("INSERT INTO content_draft_asset (revision_id, position, attachment_id, source_fingerprint)"
                + " VALUES (1, 0, 1, '" + FINGERPRINT + "')");
        }
        migrate(database, MOVED);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            // 직전 이미지로 되돌린 동안 옛 코드가 쓴 줄이다. 3번 revision 은 옛 코드가 만들었고 2번 snapshot 의 job 은 옛 테이블에만 있다.
            statement.executeUpdate(job(8, 2, 32, "PREVIEW_READY"));
            // 3번 snapshot 에는 새 코드가 이미 옮긴 job 이 있어 같은 snapshot 의 옛 job 을 덮어쓰지 않는다.
            statement.executeUpdate("UPDATE content_draft_snapshot SET action_id = 41, job_status = 'DISPATCHED' WHERE id = 3");
            statement.executeUpdate(job(9, 3, 33, "FAILED"));
            statement.executeUpdate("INSERT INTO content_draft_revision (id, draft_id, revision, status, schema_version, created_at, body,"
                + " body_key_id) VALUES (3, 1, 3, 'VALID', 1, CURRENT_TIMESTAMP, 'sealed', 1)");
            statement.executeUpdate("INSERT INTO content_draft_revision (id, draft_id, revision, status, schema_version, created_at, body,"
                + " body_key_id) VALUES (4, 1, 4, 'VALID', 1, CURRENT_TIMESTAMP, 'sealed', 1)");
            statement.executeUpdate("INSERT INTO content_draft_asset (revision_id, position, attachment_id, source_fingerprint, observation_id,"
                + " observation_revision) VALUES (3, 1, 2, '" + FINGERPRINT + "', 6, 1), (3, 0, 1, '" + FINGERPRINT + "', NULL, NULL)");
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(row(statement, "SELECT action_id, job_status FROM content_draft_snapshot WHERE id = 1"))
                .as("처음 옮긴 줄은 그대로다").containsExactly("31", "SUCCEEDED");
            assertThat(row(statement, "SELECT action_id, job_status FROM content_draft_snapshot WHERE id = 2"))
                .as("옛 테이블에만 있던 job 을 옮긴다").containsExactly("32", "PREVIEW_READY");
            assertThat(row(statement, "SELECT action_id, job_status FROM content_draft_snapshot WHERE id = 3"))
                .as("이미 job 칸이 찬 snapshot 은 덮어쓰지 않는다").containsExactly("41", "DISPATCHED");
            assertThat(assets(statement, 1)).hasSize(1);
            assertThat(assets(statement, 3)).containsExactly(
                new ContentDraftAsset(0, 1L, FINGERPRINT, null, null),
                new ContentDraftAsset(1, 2L, FINGERPRINT, 6L, 1L));
            assertThat(assets(statement, 4)).as("사진이 없는 revision 은 빈 배열이다").isEmpty();
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE content_draft_revision SET assets_json = NULL WHERE id = 1"))
                .as("사진 참조 칸은 NOT NULL 이다").isInstanceOf(SQLException.class);
            for (String table : List.of("content_draft_job", "content_draft_asset")) {
                assertThatThrownBy(() -> row(statement, "SELECT COUNT(*) FROM " + table))
                    .as("옛 테이블은 지운다").isInstanceOf(SQLException.class);
            }
        }
    }


    private static String job(int id, int snapshotId, int actionId, String status) {
        return "INSERT INTO content_draft_job (id, snapshot_id, action_id, owner_user_id, conversation_id, status, created_at)"
            + " VALUES (" + id + ", " + snapshotId + ", " + actionId + ", 1, 1, '" + status + "', '2026-11-01 01:02:03')";
    }


    private static void parents(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO conversation (id, public_id, user_id, title, created_at, updated_at)"
            + " VALUES (1, X'00000000000000000000000000000001', 1, '검사', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        for (int id = 1; id <= 2; id++) {
            statement.executeUpdate("INSERT INTO chat_attachment (id, conversation_id, uploaded_by_user_id, original_name, stored_name,"
                + " content_type, byte_size, expires_at, created_at, position) VALUES (" + id + ", 1, 1, 'a.png', '" + id
                + ".png', 'image/png', 1, '2099-01-01 00:00:00', '2020-01-01 00:00:00', " + id + ")");
        }
        statement.executeUpdate("INSERT INTO content_draft (id, public_id, conversation_id, owner_user_id, current_revision, created_at,"
            + " expires_at) VALUES (1, '00000000-0000-4000-8000-000000000001', 1, 1, 2, CURRENT_TIMESTAMP, '2099-01-01 00:00:00')");
        for (int revision = 1; revision <= 2; revision++) {
            statement.executeUpdate("INSERT INTO content_draft_revision (id, draft_id, revision, status, schema_version, created_at, body,"
                + " body_key_id) VALUES (" + revision + ", 1, " + revision + ", 'VALID', 1, CURRENT_TIMESTAMP, 'sealed', 1)");
        }
    }


    private static String snapshot(int id) {
        return "INSERT INTO content_draft_snapshot (id, public_id, revision_id, owner_user_id, conversation_id, origin_execution_id,"
            + " agent_id, connection_id, binding_id, connection_updated_at, binding_updated_at, policy_fingerprint, schema_version,"
            + " compiler_version, payload_fingerprint, snapshot_hash, authorization_fingerprint, created_at, expires_at) VALUES ("
            + id + ", X'" + String.format("%032x", id) + "', " + id + ", 1, 1, 1, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '"
            + FINGERPRINT + "', 1, 'content-compiler-v1', '" + FINGERPRINT + "', '" + FINGERPRINT + "', '" + FINGERPRINT
            + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
    }


    private static List<ContentDraftAsset> assets(Statement statement, long revisionId) throws SQLException {
        var value = row(statement, "SELECT assets_json FROM content_draft_revision WHERE id = " + revisionId).getFirst();
        assertThat(value).as("%d번 revision 의 assets_json", revisionId).isNotNull();
        return new ContentDraftAssetsConverter().convertToEntityAttribute(value);
    }


    /** 첫 줄의 칸을 문자열로 읽는다. 시각은 마이크로초까지, 참거짓은 소문자로 맞춘다. */
    private static List<String> row(Statement statement, String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                Object value = rows.getObject(column);
                values.add(switch (value) {
                    case null -> null;
                    case Boolean flag -> flag.toString();
                    case Timestamp at -> MICROS.format(at.toLocalDateTime());
                    case LocalDateTime at -> MICROS.format(at);
                    default -> value.toString();
                });
            }
        }
        return values;
    }


    private static void migrate(Database database, String target) {
        var configuration = Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .outOfOrder(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    private static Connection connect(Database database) throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
