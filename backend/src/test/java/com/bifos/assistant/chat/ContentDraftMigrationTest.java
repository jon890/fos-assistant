package com.bifos.assistant.chat;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentDraftMigrationTest {
    private String[] database;

    String[] createDatabase() {
        return new String[] {"jdbc:h2:mem:draft-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""};
    }

    @BeforeEach
    void migrate() throws SQLException {
        database = createDatabase();
        Flyway.configure().dataSource(database[0], database[1], database[2]).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        execute("insert into conversation(id,public_id,user_id,title,created_at,updated_at) values(1,X'00000000000000000000000000000001',1,'검사',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        execute("insert into chat_attachment(id,conversation_id,message_id,uploaded_by_user_id,original_name,stored_name,content_type,byte_size,position,created_at,expires_at) values(1,1,100,1,'a.gif','1.gif','image/gif',9,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        execute("insert into content_draft(id,public_id,conversation_id,owner_user_id,current_revision,created_at,expires_at) values(1,'00000000-0000-0000-0000-000000000002',1,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        execute("insert into content_draft_revision(id,draft_id,revision,status,schema_version,created_at,body,body_key_id) values(1,1,1,'VALID',1,CURRENT_TIMESTAMP,'sealed',1)");
    }

    @Test
    @DisplayName("본문과 key CHECK revision UNIQUE 관찰 쌍과 position을 강제한다")
    void enforcesAtomicBodyChecksRevisionUniqueAndAssetPairs() throws SQLException {
        for (String change : List.of("body=null", "body_key_id=null", "status='STALE'", "status='OTHER'", "schema_version=2", "revision=0")) {
            assertThatThrownBy(() -> execute("update content_draft_revision set " + change + " where id=1")).isInstanceOf(SQLException.class);
        }
        assertThatThrownBy(() -> execute("insert into content_draft_revision(draft_id,revision,status,schema_version,created_at,body,body_key_id) values(1,1,'VALID',1,CURRENT_TIMESTAMP,'sealed',1)"))
            .isInstanceOf(SQLException.class);
        for (String pair : List.of("1,null", "null,1", "0,1", "1,0")) {
            assertThatThrownBy(() -> execute("insert into content_draft_asset(revision_id,position,attachment_id,source_fingerprint,observation_id,observation_revision) values(1,0,1,'" + "a".repeat(64) + "'," + pair + ")"))
                .isInstanceOf(SQLException.class);
        }
        assertThatThrownBy(() -> execute("insert into content_draft_asset(revision_id,position,attachment_id,source_fingerprint) values(1,500,1,'" + "a".repeat(64) + "')"))
            .isInstanceOf(SQLException.class);
        execute("update content_draft_revision set status='STALE',body=null,body_key_id=null where id=1");
        execute("update content_draft_revision set status='EXPIRED' where id=1");
    }

    @Test
    @DisplayName("첨부와 대화 FK는 tombstone을 보존하고 원고 먼저 purge하면 자식도 지운다")
    void restrictsAttachmentDeletionAndCascadesDraftPurge() throws SQLException {
        execute("insert into content_draft_asset(revision_id,position,attachment_id,source_fingerprint,observation_id,observation_revision) values(1,0,1,'" + "a".repeat(64) + "',999,1)");
        assertThatThrownBy(() -> execute("delete from chat_attachment where id=1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("delete from conversation where id=1")).isInstanceOf(SQLException.class);
        execute("delete from content_draft where id=1");
        try (var connection = DriverManager.getConnection(database[0], database[1], database[2]); var statement = connection.createStatement();
            var rows = statement.executeQuery("select (select count(*) from content_draft_asset)+(select count(*) from content_draft_revision)")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
        execute("delete from chat_attachment where id=1");
        execute("delete from conversation where id=1");
    }

    private void execute(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(database[0], database[1], database[2]); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
