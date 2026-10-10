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
            insertObservation(statement, 1, 1, 1);
            insertObservation(statement, 2, 2, 1);
        }
    }

    @Test
    @DisplayName("요청 유일성과 복합 외래키 revision 유일성을 강제한다")
    void enforcesRequestUniqueCompositeForeignKeyAndRevisionUnique() throws SQLException {
        try (var connection = connect();
                var statement = connection.createStatement()) {
            alias(statement, 1, 1, "00000000-0000-0000-0000-000000000001", "a");
            assertThatThrownBy(() -> alias(statement, 1, 1, "00000000-0000-0000-0000-000000000001", "b"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> alias(statement, 1, 2, "00000000-0000-0000-0000-000000000002", "a"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertObservation(statement, 3, 1, 1)).isInstanceOf(SQLException.class);
            assertThat(count(statement, "media_observation_request")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("관찰과 첨부 삭제가 모든 alias에 전파된다")
    void cascadesObservationAndAttachmentDeletionToEveryAlias() throws SQLException {
        try (var connection = connect();
                var statement = connection.createStatement()) {
            alias(statement, 1, 1, UUID.randomUUID().toString(), "a");
            alias(statement, 1, 1, UUID.randomUUID().toString(), "b");
            alias(statement, 2, 2, UUID.randomUUID().toString(), "c");
            statement.executeUpdate("delete from media_observation where id=1");
            assertThat(count(statement, "media_observation_request")).isEqualTo(1);
            statement.executeUpdate("delete from chat_attachment where id=2");
            assertThat(count(statement, "media_observation")).isZero();
            assertThat(count(statement, "media_observation_request")).isZero();
        }
    }

    @Test
    @DisplayName("nullable 분석 key와 기존 긴 본문 색인을 보존하고 cache 색인은 만들지 않는다")
    void createsNullableAnalysisKeyAndPreservesBodyAndIndexes() throws SQLException {
        try (var connection = connect()) {
            try (var columns = connection.getMetaData().getColumns(null, null, table(connection), "%")) {
                boolean body = false;
                boolean key = false;
                while (columns.next()) {
                    String name = columns.getString("COLUMN_NAME");
                    if ("analysis_key".equalsIgnoreCase(name)) {
                        key = true;
                        assertThat(columns.getInt("DATA_TYPE")).isEqualTo(Types.CHAR);
                        assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(64);
                        assertThat(columns.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNullable);
                    }
                    if ("body".equalsIgnoreCase(name)) {
                        body = true;
                        assertThat(columns.getLong("COLUMN_SIZE")).isGreaterThanOrEqualTo(65536);
                    }
                }
                assertThat(body).isTrue();
                assertThat(key).isTrue();
            }
            try (var indexes = connection.getMetaData().getIndexInfo(null, null, table(connection), false, false)) {
                boolean found = false;
                while (indexes.next()) {
                    assertThat(indexes.getString("COLUMN_NAME")).isNotEqualToIgnoringCase("analysis_key");
                    if ("ix_media_observation_conversation".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                        found = true;
                    }
                }
                assertThat(found).isTrue();
            }
            try (var statement = connection.createStatement()) {
                assertThat(count(statement, "media_observation where analysis_key is null"))
                        .isEqualTo(2);
                insertObservation(statement, 3, 1, 2);
                statement.executeUpdate("update media_observation set analysis_key='" + "a".repeat(64) + "'");
                assertThat(count(statement, "media_observation")).isEqualTo(3);
            }
        }
    }

    @Test
    @DisplayName("cache DDL 직전의 본문과 다중 alias를 보존하고 구행 key는 null로 둔다")
    void upgradesLegacyRowsAndAliasesWithoutBackfill() throws SQLException {
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
            insertObservation(statement, 1, 1, 1);
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
                            "media_observation where analysis_key is null and body='legacy-body' and body_key_id=42"))
                    .isEqualTo(1);
            assertThat(count(statement, "media_observation_request")).isEqualTo(2);
            assertThatThrownBy(() -> alias(statement, 1, 1, "00000000-0000-0000-0000-000000000001", "c"))
                    .isInstanceOf(SQLException.class);
            statement.executeUpdate("delete from media_observation where id=1");
            assertThat(count(statement, "media_observation_request")).isZero();
        }
    }

    private String table(Connection connection) throws SQLException {
        return connection.getMetaData().storesUpperCaseIdentifiers() ? "MEDIA_OBSERVATION" : "media_observation";
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database[0], database[1], database[2]);
    }

    private static void insertObservation(Statement statement, long id, long attachment, long revision)
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
