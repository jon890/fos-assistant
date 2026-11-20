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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 모든 마이그레이션을 적용한 지금 스키마에서 관찰 표의 유일 제약, NOT NULL, cascade, 색인을 확인한다.
 *
 * <p>요청 칸을 옮기던 데이터 검사는 그 마이그레이션이 운영에 적용된 지 14일이 지나 지웠다. 기준은
 * backend/docs/data-schema.md 의 「운영에 적용된 지 14일이 지난 마이그레이션의 데이터 검사는 지운다」 에 있다.
 *
 * <p>마이그레이션은 클래스마다 한 번만 적용하고 검사마다 줄만 다시 넣는다. 실제 MySQL 에서 전체 적용은 한 번에 13~22초가 든다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaObservationSchemaTest {
    private String[] database;


    String[] createDatabase() {
        return new String[] {"jdbc:h2:mem:observations-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""
        };
    }


    @BeforeAll
    void migrate() {
        database = createDatabase();
        var flyway = Flyway.configure()
            .dataSource(database[0], database[1], database[2])
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .load();
        flyway.migrate();
        assertThat(flyway.info().pending()).isEmpty();
    }


    /** 앞 검사가 넣거나 지운 줄을 비우고 같은 두 첨부와 두 관찰 줄로 되돌린다. */
    @BeforeEach
    void resetRows() throws SQLException {
        try (var connection = connect();
            var statement = connection.createStatement()) {
            statement.executeUpdate("delete from media_observation");
            statement.executeUpdate("delete from chat_attachment");
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


    private static long count(Statement statement, String table) throws SQLException {
        try (var row = statement.executeQuery("select count(*) from " + table)) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }
}
