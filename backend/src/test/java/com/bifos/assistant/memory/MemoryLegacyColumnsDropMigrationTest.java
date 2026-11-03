package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기억의 옛 민감도 칸과 옛 key 칸, 에이전트의 옛 민감 허용 칸을 지우는 마이그레이션을 본다(ADR-20261103 /
 * memory-sensitivity-removal). 옛 key 로 암호화한 줄이 남았으면 아무 칸도 지우지 않고 멈추는 것과, 없으면 줄을 그대로 둔 채
 * 칸만 사라지는 것을 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들어 옛 칸이 없으므로 운영과 같은 Flyway 스키마를 여기서 따로 만든다.
 */
class MemoryLegacyColumnsDropMigrationTest {

    /** 옛 key 줄을 확인하는 마이그레이션 바로 앞 버전이다. 이 버전까지는 옛 칸이 남아 있다. */
    private static final String BEFORE = "20261103054413";

    /** 지울 칸이다. 표 이름과 칸 이름. */
    private static final Map<String, List<String>> LEGACY_COLUMNS = Map.of(
        "memory", List.of("sensitivity", "content_key_id"),
        "memory_revision", List.of("sensitivity", "content_key_id"),
        "agent_memory_collection", List.of("allow_sensitive"),
        "agent_memory_collection_change", List.of("allow_sensitive"));


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database("jdbc:h2:mem:memory-legacy-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("옛 key 로 암호화한 줄이 남았으면 건수를 알리고 멈추며 어느 칸도 지우지 않는다")
    void stopsWhenLegacyKeyRowsRemain() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            insertMemory(statement, 1, "NULL");
            insertMemory(statement, 2, "'old-key'");
            insertRevision(statement, 2, "'old-key'");
        }

        assertThatThrownBy(() -> migrate(database, null))
            .hasStackTraceContaining("memory 1 줄, memory_revision 1 줄");

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            LEGACY_COLUMNS.forEach((table, columns) -> columns.forEach(column -> assertThat(exists(statement, table, column))
                .as("%s.%s 는 그대로다", table, column)
                .isTrue()));
        }
    }


    @Test
    @DisplayName("옛 key 줄이 없으면 줄은 그대로 두고 여섯 칸만 지운다")
    void dropsSixColumnsAndKeepsRows() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            insertMemory(statement, 1, "NULL");
            insertMemory(statement, 2, "NULL");
            insertRevision(statement, 2, "NULL");
            statement.execute(connection.getMetaData().getDatabaseProductName().equals("MySQL")
                ? "SET FOREIGN_KEY_CHECKS = 0"
                : "SET REFERENTIAL_INTEGRITY FALSE");
            statement.executeUpdate("INSERT INTO agent_memory_collection (agent_id, collection, allow_sensitive, created_at)"
                + " VALUES (9001, 'career', TRUE, CURRENT_TIMESTAMP(6))");
            statement.executeUpdate("INSERT INTO agent_memory_collection_change (agent_id, collection, change_type, allow_sensitive,"
                + " changed_by_user_id, changed_at) VALUES (9001, 'career', 'SENSITIVE_CHANGED', TRUE, 1, CURRENT_TIMESTAMP(6))");
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            LEGACY_COLUMNS.forEach((table, columns) -> columns.forEach(column -> assertThat(exists(statement, table, column))
                .as("%s.%s 는 지웠다", table, column)
                .isFalse()));
            assertThat(count(statement, "SELECT COUNT(*) FROM memory WHERE title = '제목 1' AND content = '본문 1' AND data_key_id = 7"))
                .isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM memory")).isEqualTo(2);
            assertThat(count(statement, "SELECT COUNT(*) FROM memory_revision WHERE memory_id = 2 AND content = '본문 2'")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM agent_memory_collection WHERE agent_id = 9001 AND collection = 'career'"))
                .isEqualTo(1);
            assertThat(count(statement,
                "SELECT COUNT(*) FROM agent_memory_collection_change WHERE agent_id = 9001 AND change_type = 'SENSITIVE_CHANGED'"))
                    .as("옛 변경 기록의 줄은 남는다")
                    .isEqualTo(1);
        }
    }


    private static void insertMemory(Statement statement, long id, String contentKeyId) throws SQLException {
        statement.executeUpdate(String.format("""
                INSERT INTO memory (id, scope, owner_user_id, collection, entry_type, title, content, retrieval, always_inject,
                    sensitivity, content_key_id, data_key_id, revision, status, created_at, updated_at)
                VALUES (%d, 'USER', 1, 'core', 'MEMORY', '제목 %d', '본문 %d', 'SEARCH', FALSE, 'SENSITIVE', %s, 7, 1, 'ACCEPTED',
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, id, id, id, contentKeyId));
    }


    private static void insertRevision(Statement statement, long memoryId, String contentKeyId) throws SQLException {
        statement.executeUpdate(String.format("""
                INSERT INTO memory_revision (memory_id, revision, change_type, scope, owner_user_id, collection, entry_type, status,
                    title, content, retrieval, sensitivity, content_key_id, data_key_id, changed_at)
                VALUES (%d, 1, 'UPDATED', 'USER', 1, 'core', 'MEMORY', 'ACCEPTED', '제목 %d', '본문 %d', 'SEARCH', 'SENSITIVE',
                    %s, 7, CURRENT_TIMESTAMP(6))
                """, memoryId, memoryId, memoryId, contentKeyId));
    }


    /** 칸을 읽어 보고 오류가 나면 없는 칸으로 본다. 정보 스키마의 이름 대소문자가 DB 마다 달라 쓰지 않는다. */
    private static boolean exists(Statement statement, String table, String column) {
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


    private static void migrate(Database database, String target) {
        var configuration = Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration");
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
