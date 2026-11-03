package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 효력 있는 대체 연결({@code memory_link} 의 {@code SUPERSEDES}, {@code ACTIVE})을 옛 항목의 {@code superseded_by_memory_id} 로 옮기는
 * 마이그레이션을 본다(ADR-20261102 / table-reduce-r2).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로 운영과 같은 Flyway 스키마는 여기서 따로 만든다.
 */
class MemorySupersedeMigrationTest {

    /** 옮기는 마이그레이션 바로 앞 버전이다. */
    private static final String BEFORE = "20261103054413";


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:memory-supersede-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("효력 있는 대체 연결만 옛 항목의 칸으로 옮기고 여럿이면 가장 나중에 맺은 줄의 새 항목을 쓰며 옛 줄은 지우지 않는다")
    void movesOnlyActiveSupersedeLinks() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        long[] ids = new long[7];
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            for (int i = 0; i < ids.length; i++) {
                ids[i] = insertMemory(statement, "항목 " + i);
            }
            // 0 번 항목은 효력 있는 대체 둘이 가리킨다. 번호가 큰 줄(2 번 새 항목)이 이긴다
            insertLink(statement, ids[1], ids[0], "SUPERSEDES", "ACTIVE");
            insertLink(statement, ids[2], ids[0], "SUPERSEDES", "ACTIVE");
            // 3 번 항목은 제안 중이거나 끊은 대체만 가리킨다. 옮기지 않는다
            insertLink(statement, ids[4], ids[3], "SUPERSEDES", "PROPOSED");
            insertLink(statement, ids[5], ids[3], "SUPERSEDES", "REVOKED");
            // 관련 연결은 옮기지 않는다
            insertLink(statement, ids[5], ids[6], "RELATED", "ACTIVE");
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT id FROM memory WHERE superseded_by_memory_id IS NOT NULL ORDER BY id"))
                .as("칸이 찬 항목")
                .containsExactly(String.valueOf(ids[0]));
            assertThat(strings(statement, "SELECT superseded_by_memory_id FROM memory WHERE id = " + ids[0]))
                .containsExactly(String.valueOf(ids[2]));
            assertThat(strings(statement, "SELECT COUNT(*) FROM memory_link"))
                .as("직전 이미지가 읽도록 옛 줄은 지우지 않는다")
                .containsExactly("5");
        }
    }


    @Test
    @DisplayName("옮길 대체 연결이 없으면 어느 항목의 칸도 채우지 않는다")
    void leavesColumnEmptyWithoutActiveSupersedeLinks() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            long first = insertMemory(statement, "첫째");
            long second = insertMemory(statement, "둘째");
            insertLink(statement, first, second, "RELATED", "ACTIVE");
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT COUNT(*) FROM memory WHERE superseded_by_memory_id IS NOT NULL"))
                .containsExactly("0");
        }
    }


    private static long insertMemory(Statement statement, String title) throws SQLException {
        statement.executeUpdate(
            "INSERT INTO memory (scope, owner_user_id, collection, entry_type, title, content, retrieval, always_inject,"
                + " status, revision, created_at, updated_at)"
                + " VALUES ('USER', 1, 'core', 'MEMORY', '" + title + "', '내용', 'SEARCH', FALSE, 'ACCEPTED', 1,"
                + " CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            Statement.RETURN_GENERATED_KEYS);
        try (ResultSet keys = statement.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }


    private static void insertLink(Statement statement, long from, long to, String type, String status) throws SQLException {
        statement.executeUpdate(
            "INSERT INTO memory_link (from_memory_id, to_memory_id, type, origin, status, created_at, updated_at)"
                + " VALUES (" + from + ", " + to + ", '" + type + "', 'AGENT', '" + status + "',"
                + " CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
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


    private static Connection connect(Database database) throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
