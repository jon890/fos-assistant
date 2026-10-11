package com.bifos.assistant.chat;

import java.util.HashMap;
import java.util.ArrayList;
import java.sql.Connection;
import java.util.Locale;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentDraftSnapshotMigrationTest {
    private String[] database;

    String[] createDatabase() {
        return new String[] {"jdbc:h2:mem:snapshot-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""};
    }

    @BeforeEach
    void migrate() throws SQLException {
        database = createDatabase();
        Flyway.configure().dataSource(database[0], database[1], database[2]).locations("classpath:db/migration")
            .outOfOrder(true).target("20261011060038").load().migrate();
        execute("insert into conversation(id,public_id,user_id,title,created_at,updated_at) values(1,X'00000000000000000000000000000001',1,'검사',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        execute("insert into content_draft(id,public_id,conversation_id,owner_user_id,current_revision,created_at,expires_at) values(1,'00000000-0000-0000-0000-000000000002',1,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        execute("insert into content_draft_revision(id,draft_id,revision,status,schema_version,created_at,body,body_key_id) values(1,1,1,'VALID',1,CURRENT_TIMESTAMP,'sealed',1)");
        Flyway.configure().dataSource(database[0], database[1], database[2]).locations("classpath:db/migration")
            .outOfOrder(true).load().migrate();
        assertThat(count("content_draft_revision")).isEqualTo(1);
    }

    @Test
    @DisplayName("두 표의 NULL 유일 제약 논리 참조와 terminal 이력을 보존한다")
    void enforcesUniqueAndNullableColumnsWithoutCascadingHistory() throws Exception {
        execute(snapshot(1, 1, 1));
        assertThatThrownBy(() -> execute(snapshot(2, 1, 1))).isInstanceOf(SQLException.class);
        execute(snapshot(3, 2, 1));
        execute(job(1, 1, 1));
        execute(job(2, 3, 2));
        assertThatThrownBy(() -> execute(job(3, 1, 3))).isInstanceOf(SQLException.class);
        execute("update content_draft_job set status='UNKNOWN', job_id='11111111-1111-4111-8111-111111111111' where id=1");
        assertThatThrownBy(() -> execute("update content_draft_job set job_id='11111111-1111-4111-8111-111111111111' where id=2"))
            .isInstanceOf(SQLException.class);
        for (String table : List.of("content_draft_snapshot", "content_draft_job")) {
            try (var connection = DriverManager.getConnection(database[0], database[1], database[2]);
                var rows = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, tableName(connection, table))) {
                assertThat(rows.next()).isFalse();
            }
        }
        execute("delete from content_draft where id=1");
        assertThat(count("content_draft_snapshot")).isEqualTo(2);
        assertThat(count("content_draft_job")).isEqualTo(2);
    }

    @Test
    @DisplayName("snapshot 칸에는 본문이 없고 job 승인 칸은 nullable이며 owner 대화 색인이 있다")
    void keepsReferencesOnlyWithOwnerConversationIndexes() throws Exception {
        for (String table : List.of("content_draft_snapshot", "content_draft_job")) {
            try (var connection = DriverManager.getConnection(database[0], database[1], database[2]);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("select * from " + table + " where 1=0")) {
                var metadata = rows.getMetaData();
                var columns = new TreeSet<String>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    columns.add(metadata.getColumnName(i).toLowerCase());
                }
                assertThat(columns).doesNotContain("body", "payload", "canonical_blocks", "photo_notes", "ticket", "image");
                assertThat(columns).contains("owner_user_id", "conversation_id", "id");
                try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, tableName(connection, table), false, false)) {
                    var found = new HashMap<String, List<String>>();
                    while (indexes.next()) {
                        String name = indexes.getString("INDEX_NAME");
                        if (name != null) {
                            found.computeIfAbsent(name, ignored -> new ArrayList<>()).add(indexes.getString("COLUMN_NAME").toLowerCase());
                        }
                    }
                    assertThat(found.values()).contains(List.of("owner_user_id", "conversation_id", "id"));
                }
            }
        }
        execute(snapshot(1, 1, 1));
        execute(job(1, 1, 1));
        for (String key : Set.of("public_id", "revision_id", "owner_user_id", "conversation_id", "origin_execution_id", "binding_id", "policy_fingerprint")) {
            assertThatThrownBy(() -> execute("update content_draft_snapshot set " + key + "=null where id=1")).isInstanceOf(SQLException.class);
        }
    }

    private String snapshot(int id, int revisionId, int bindingId) {
        return "insert into content_draft_snapshot(id,public_id,revision_id,owner_user_id,conversation_id,origin_execution_id,agent_id,connection_id,binding_id,connection_updated_at,binding_updated_at,policy_fingerprint,schema_version,compiler_version,payload_fingerprint,snapshot_hash,created_at,expires_at) values("
            + id + ",X'" + String.format("%032x", id) + "'," + revisionId + ",1,1,1,1,1," + bindingId
            + ",CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'" + "a".repeat(64) + "',1,'content-compiler-v1','" + "b".repeat(64)
            + "','" + "c".repeat(64) + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
    }

    private String job(int id, int snapshotId, int actionId) {
        return "insert into content_draft_job(id,snapshot_id,action_id,owner_user_id,conversation_id,status,created_at) values("
            + id + "," + snapshotId + "," + actionId + ",1,1,'PREVIEW_READY',CURRENT_TIMESTAMP)";
    }

    private int count(String table) throws SQLException {
        try (var connection = DriverManager.getConnection(database[0], database[1], database[2]);
            var statement = connection.createStatement(); var rows = statement.executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String tableName(Connection connection, String table) throws SQLException {
        return connection.getMetaData().storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT) : table;
    }

    private void execute(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(database[0], database[1], database[2]); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
