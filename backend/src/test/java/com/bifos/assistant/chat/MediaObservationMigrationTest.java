package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MediaObservationMigrationTest {
    /** 요청 칸을 더하는 마이그레이션보다 앞선 버전이다. 그 사이 버전은 out-of-order 로 함께 적용된다. */
    private static final String BEFORE_REQUEST_MERGE = "20261102025855";

    /** 요청 칸을 더하고 옮긴 뒤 옛 표를 아직 지우지 않은 마지막 버전이다. 직전 이미지가 이 스키마에서 돈다. */
    private static final String BEFORE_REFILL = "20261102074304";

    private String[] database;


    String[] createDatabase() {
        return new String[] {"jdbc:h2:mem:observations-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""
        };
    }


    @BeforeEach
    void migrate() throws SQLException {
        database = createDatabase();
        var flyway = Flyway.configure()
            .dataSource(database[0], database[1], database[2])
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .load();
        flyway.migrate();
        assertThat(flyway.info().pending()).isEmpty();
        try (var connection = connect();
            var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into chat_attachment (id,conversation_id,message_id,uploaded_by_user_id,original_name,
                    stored_name,content_type,byte_size,position,created_at,expires_at)
                    values (1,1,100,1,'a.gif','1.gif','image/gif',9,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                    (2,1,100,1,'b.gif','2.gif','image/gif',9,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            insertObservation(statement, 1, 1, 1, "00000000-0000-0000-0000-000000000001");
            insertObservation(statement, 2, 2, 1, "00000000-0000-0000-0000-000000000002");
        }
    }


    @Test
    @DisplayName("같은 첨부의 같은 요청 UUID 와 같은 revision 을 막고 요청 칸이 비는 줄을 막는다")
    void enforcesRequestUniqueRevisionUniqueAndNotNull() throws SQLException {
        try (var connection = connect();
            var statement = connection.createStatement()) {
            assertThatThrownBy(() -> insertObservation(statement, 3, 1, 2, "00000000-0000-0000-0000-000000000001"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertObservation(statement, 3, 1, 1, "00000000-0000-0000-0000-000000000003"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertObservation(statement, 3, 1, 2, null)).isInstanceOf(SQLException.class);
            insertObservation(statement, 3, 2, 2, "00000000-0000-0000-0000-000000000001");
            assertThat(count(statement, "media_observation")).isEqualTo(3);
        }
    }


    @Test
    @DisplayName("옛 요청 표와 분석 key 칸은 없고 첨부 삭제가 관찰로 전파된다")
    void dropsOldRequestTableAndAnalysisKeyAndCascadesAttachmentDeletion() throws SQLException {
        try (var connection = connect();
            var statement = connection.createStatement()) {
            assertThatThrownBy(() -> count(statement, "media_observation_request")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> count(statement, "media_observation where analysis_key is null"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("delete from chat_attachment where id=2");
            assertThat(count(statement, "media_observation")).isEqualTo(1);
        }
    }


    @Test
    @DisplayName("요청 칸은 NOT NULL 이고 기존 긴 본문 색인을 보존하며 분석 key 색인은 만들지 않는다")
    void hasNotNullRequestColumnsAndPreservesBodyAndIndexes() throws SQLException {
        try (var connection = connect()) {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table(connection), "%")) {
                boolean body = false;
                int requestColumns = 0;
                while (columns.next()) {
                    String name = columns.getString("COLUMN_NAME");
                    assertThat(name).isNotEqualToIgnoringCase("analysis_key");
                    if ("request_id".equalsIgnoreCase(name) || "request_hash".equalsIgnoreCase(name)) {
                        requestColumns++;
                        assertThat(columns.getInt("DATA_TYPE")).isEqualTo(Types.CHAR);
                        assertThat(columns.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNoNulls);
                    }
                    if ("body".equalsIgnoreCase(name)) {
                        body = true;
                        assertThat(columns.getLong("COLUMN_SIZE")).isGreaterThanOrEqualTo(65536);
                    }
                }
                assertThat(body).isTrue();
                assertThat(requestColumns).isEqualTo(2);
            }
            try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table(connection), false, false)) {
                boolean found = false;
                while (indexes.next()) {
                    assertThat(indexes.getString("COLUMN_NAME")).isNotEqualToIgnoringCase("analysis_key");
                    if ("ix_media_observation_conversation".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                        found = true;
                    }
                }
                assertThat(found).isTrue();
            }
        }
    }


    @Test
    @DisplayName("재사용 DDL 직전의 본문과 다중 alias를 올리면 본문은 보존하고 가장 먼저 저장한 요청을 관찰 줄로 옮긴다")
    void upgradesLegacyRowsAndAliases() throws SQLException {
        String[] before = createDatabase();
        Flyway.configure()
            .dataSource(before[0], before[1], before[2])
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .target("20261010055921")
            .load()
            .migrate();
        try (var connection = DriverManager.getConnection(before[0], before[1], before[2]);
            var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into chat_attachment (id,conversation_id,message_id,uploaded_by_user_id,original_name,
                    stored_name,content_type,byte_size,position,created_at,expires_at)
                    values (1,1,100,1,'a.gif','1.gif','image/gif',9,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            insertLegacyObservation(statement, 1, 1, 1);
            statement.executeUpdate("update media_observation set body='legacy-body',body_key_id=42");
            alias(statement, 1, 1, "00000000-0000-0000-0000-000000000001", "a");
            alias(statement, 1, 1, "00000000-0000-0000-0000-000000000002", "b");
            var upgraded = Flyway.configure()
                .dataSource(before[0], before[1], before[2])
                .locations("classpath:db/migration")
                .outOfOrder(true)
                .load();
            upgraded.migrate();
            upgraded.validate();
            assertThat(count(
                statement,
                "media_observation where body='legacy-body' and body_key_id=42"
                    + " and request_id='00000000-0000-0000-0000-000000000001' and request_hash='" + "a".repeat(64) + "'"))
                        .isEqualTo(1);
            assertThatThrownBy(() -> count(statement, "media_observation_request")).isInstanceOf(SQLException.class);
        }
    }


    @Test
    @DisplayName("요청 칸 이관은 관찰마다 가장 먼저 저장한 요청을 옮기고 같은 첨부의 같은 요청 UUID 를 막는다")
    void backfillsEarliestRequestIntoObservationAndEnforcesRequestUnique() throws SQLException {
        String[] before = createDatabase();
        Flyway.configure()
            .dataSource(before[0], before[1], before[2])
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .target(BEFORE_REQUEST_MERGE)
            .load()
            .migrate();
        try (var connection = DriverManager.getConnection(before[0], before[1], before[2]);
            var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into chat_attachment (id,conversation_id,message_id,uploaded_by_user_id,original_name,
                    stored_name,content_type,byte_size,position,created_at,expires_at)
                    values (1,1,100,1,'a.gif','1.gif','image/gif',9,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            insertLegacyObservation(statement, 1, 1, 1);
            insertLegacyObservation(statement, 2, 1, 2);
            alias(statement, 1, 1, "00000000-0000-0000-0000-000000000001", "a");
            alias(statement, 1, 1, "00000000-0000-0000-0000-000000000002", "b");
            alias(statement, 1, 2, "00000000-0000-0000-0000-000000000003", "c");
            Flyway.configure()
                .dataSource(before[0], before[1], before[2])
                .locations("classpath:db/migration")
                .outOfOrder(true)
                .load()
                .migrate();
            assertThat(count(statement, "media_observation where id=1 and request_id='00000000-0000-0000-0000-000000000001'"
                + " and request_hash='" + "a".repeat(64) + "'")).isEqualTo(1);
            assertThat(count(statement, "media_observation where id=2 and request_id='00000000-0000-0000-0000-000000000003'"
                + " and request_hash='" + "c".repeat(64) + "'")).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate(
                "update media_observation set request_id='00000000-0000-0000-0000-000000000001' where id=2"))
                    .isInstanceOf(SQLException.class);
        }
    }


    @Test
    @DisplayName("직전 이미지가 요청 칸 없이 쓴 관찰 줄은 옛 요청 표에서 다시 채우고 이미 채운 줄은 그대로 둔다")
    void refillsRowsWrittenByPreviousImage() throws SQLException {
        String[] before = createDatabase();
        Flyway.configure()
            .dataSource(before[0], before[1], before[2])
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .target(BEFORE_REFILL)
            .load()
            .migrate();
        try (var connection = DriverManager.getConnection(before[0], before[1], before[2]);
            var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into chat_attachment (id,conversation_id,message_id,uploaded_by_user_id,original_name,
                    stored_name,content_type,byte_size,position,created_at,expires_at)
                    values (1,1,100,1,'a.gif','1.gif','image/gif',9,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            // 새 코드가 쓴 줄은 요청 칸이 차 있다. 직전 이미지가 쓴 줄은 칸이 비고 옛 요청 표에 줄이 있다.
            insertObservation(statement, 1, 1, 1, "00000000-0000-0000-0000-0000000000aa");
            statement.executeUpdate("update media_observation set request_hash='" + "e".repeat(64) + "' where id=1");
            insertLegacyObservation(statement, 2, 1, 2);
            insertLegacyObservation(statement, 3, 1, 3);
            alias(statement, 1, 2, "00000000-0000-0000-0000-000000000002", "b");
            alias(statement, 1, 2, "00000000-0000-0000-0000-000000000004", "d");
            alias(statement, 1, 3, "00000000-0000-0000-0000-000000000003", "c");

            Flyway.configure()
                .dataSource(before[0], before[1], before[2])
                .locations("classpath:db/migration")
                .outOfOrder(true)
                .load()
                .migrate();

            assertThat(count(statement, "media_observation where id=1 and request_id='00000000-0000-0000-0000-0000000000aa'"
                + " and request_hash='" + "e".repeat(64) + "'")).isEqualTo(1);
            assertThat(count(statement, "media_observation where id=2 and request_id='00000000-0000-0000-0000-000000000002'"
                + " and request_hash='" + "b".repeat(64) + "'")).isEqualTo(1);
            assertThat(count(statement, "media_observation where id=3 and request_id='00000000-0000-0000-0000-000000000003'"
                + " and request_hash='" + "c".repeat(64) + "'")).isEqualTo(1);
        }
    }


    @Test
    @DisplayName("옛 요청 표에 줄이 없는 빈 관찰 줄이 남으면 NOT NULL 단계가 실패해 옛 표를 지우지 않는다")
    void stopsBeforeDropWhenARequestCellCannotBeRefilled() throws SQLException {
        String[] before = createDatabase();
        Flyway.configure()
            .dataSource(before[0], before[1], before[2])
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .target(BEFORE_REFILL)
            .load()
            .migrate();
        try (var connection = DriverManager.getConnection(before[0], before[1], before[2]);
            var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into chat_attachment (id,conversation_id,message_id,uploaded_by_user_id,original_name,
                    stored_name,content_type,byte_size,position,created_at,expires_at)
                    values (1,1,100,1,'a.gif','1.gif','image/gif',9,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            insertLegacyObservation(statement, 1, 1, 1);

            assertThatThrownBy(() -> Flyway.configure()
                .dataSource(before[0], before[1], before[2])
                .locations("classpath:db/migration")
                .outOfOrder(true)
                .load()
                .migrate()).isInstanceOf(RuntimeException.class);

            assertThat(count(statement, "media_observation")).isEqualTo(1);
        }
    }


    private String table(Connection connection) throws SQLException {
        return connection.getMetaData().storesUpperCaseIdentifiers() ? "MEDIA_OBSERVATION" : "media_observation";
    }


    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database[0], database[1], database[2]);
    }


    private static void insertObservation(Statement statement, long id, long attachment, long revision, String request)
        throws SQLException {
        statement.executeUpdate(
            "insert into media_observation (id,attachment_id,conversation_id,owner_user_id,revision,"
                + "source_fingerprint,status,provenance_kind,schema_version,prompt_version,created_at,expires_at,"
                + "request_id,request_hash) values ("
                + id + "," + attachment + ",1,1," + revision + ",'" + "a".repeat(64)
                + "','SUCCEEDED','MODEL_RESULT',1,'media-observation-v1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,"
                + (request == null ? "null" : "'" + request + "'") + "," + (request == null ? "null" : "'" + "f".repeat(64) + "'")
                + ")");
    }


    /** 요청 칸을 모르는 직전 이미지(와 요청 칸을 더하기 전 스키마)가 쓰는 모양이다. */
    private static void insertLegacyObservation(Statement statement, long id, long attachment, long revision)
        throws SQLException {
        statement.executeUpdate(
            "insert into media_observation (id,attachment_id,conversation_id,owner_user_id,revision,"
                + "source_fingerprint,status,provenance_kind,schema_version,prompt_version,created_at,expires_at) values ("
                + id + "," + attachment + ",1,1," + revision + ",'" + "a".repeat(64)
                + "','SUCCEEDED','MODEL_RESULT',1,'media-observation-v1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }


    private static void alias(Statement statement, long attachment, long observation, String request, String hash)
        throws SQLException {
        statement.executeUpdate(
            "insert into media_observation_request (attachment_id,observation_id,request_id,request_hash) values ("
                + attachment + "," + observation + ",'" + request + "','" + hash.repeat(64) + "')");
    }


    private static long count(Statement statement, String table) throws SQLException {
        try (var row = statement.executeQuery("select count(*) from " + table)) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }
}
