package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.MemoryCollectionService;
import com.bifos.assistant.memory.domain.MemoryCollection;
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
 * 그룹이 쓰는 Memory collection 을 {@code memory_collection} 에서 {@code group_setting} 의 {@code MEMORY_COLLECTIONS}
 * 줄로 옮기는 마이그레이션을 본다(ADR-20261102 / table-reduce-r2).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로 운영과 같은 Flyway 스키마는 여기서 따로 만든다. 옮긴 JSON 은 Control
 * Plane 이 쓰는 {@link MemoryCollectionService} 의 해석으로 읽어 두 쪽의 모양이 같은지도 함께 본다.
 */
class MemoryCollectionMigrationTest {

    /** 옮기는 마이그레이션 바로 앞 버전이다. */
    private static final String BEFORE = "20261102074304";


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:memory-collection-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("기본과 다른 그룹만 옛 표의 순서대로 MEMORY_COLLECTIONS 줄 하나로 옮기고 기본과 같은 그룹은 줄을 만들지 않는다")
    void movesOnlyGroupsThatDifferFromDefaults() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            insertDefaults(statement, 1);
            insertDefaults(statement, 2);
            statement.executeUpdate("UPDATE memory_collection SET display_name = '우리 집' WHERE group_id = 2 AND collection_key = 'home'");
            statement.executeUpdate("DELETE FROM memory_collection WHERE group_id = 2 AND collection_key = 'finance'");
            insertDefaults(statement, 3);
            statement.executeUpdate("UPDATE memory_collection SET sort_order = 0 WHERE group_id = 3 AND collection_key = 'identity'");
            statement.executeUpdate("""
                    INSERT INTO memory_collection (group_id, collection_key, display_name, sort_order, created_at)
                    VALUES (4, 'pets', '반려동물', 1, CURRENT_TIMESTAMP(6)), (4, 'core', '기본', 2, CURRENT_TIMESTAMP(6))
                    """);
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT group_id FROM group_setting WHERE setting_key = 'MEMORY_COLLECTIONS' ORDER BY group_id"))
                .as("기본과 같은 1번 그룹은 줄이 없다")
                .containsExactly("2", "3", "4");
            assertThat(collectionsOf(statement, 2))
                .containsExactly(
                    new MemoryCollection("core", "기본"),
                    new MemoryCollection("career", "커리어"),
                    new MemoryCollection("learning", "학습"),
                    new MemoryCollection("health", "건강"),
                    new MemoryCollection("home", "우리 집"),
                    new MemoryCollection("identity", "신원"));
            assertThat(collectionsOf(statement, 3))
                .as("sort_order 가 앞선 collection 이 먼저다")
                .extracting(MemoryCollection::key)
                .containsExactly("identity", "core", "career", "learning", "health", "finance", "home");
            assertThat(collectionsOf(statement, 4))
                .containsExactly(new MemoryCollection("pets", "반려동물"), new MemoryCollection("core", "기본"));
            assertThat(strings(statement, "SELECT COUNT(*) FROM group_setting WHERE setting_key <> 'MEMORY_COLLECTIONS'"))
                .as("다른 설정 줄은 만들지 않는다")
                .containsExactly("0");
            assertThat(strings(statement, "SELECT COUNT(*) FROM memory_collection"))
                .as("옛 표는 직전 이미지로 되돌릴 수 있게 지우지 않는다")
                .containsExactly("22");
        }
    }


    @Test
    @DisplayName("옮기는 줄이 하나도 없거나 옛 표가 비어 있으면 줄을 만들지 않는다")
    void createsNothingWhenAllGroupsUseDefaultsOrTableIsEmpty() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            insertDefaults(statement, 1);
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT COUNT(*) FROM group_setting WHERE setting_key = 'MEMORY_COLLECTIONS'"))
                .containsExactly("0");
        }

        Database empty = createDatabase();
        migrate(empty, null);
        try (Connection connection = connect(empty); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT COUNT(*) FROM group_setting")).containsExactly("0");
        }
    }


    @Test
    @DisplayName("같은 그룹의 MEMORY_COLLECTIONS 줄이 이미 있으면 덮어쓰지 않고 멈춘다")
    void refusesToOverwriteExistingRow() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO memory_collection (group_id, collection_key, display_name, sort_order, created_at)
                    VALUES (1, 'pets', '반려동물', 1, CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate(
                "INSERT INTO group_setting (group_id, setting_key, setting_value) VALUES (1, 'MEMORY_COLLECTIONS', '{}')");
        }

        assertThatThrownBy(() -> migrate(database, null))
            .isInstanceOf(RuntimeException.class)
            .hasRootCauseInstanceOf(SQLException.class);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT setting_value FROM group_setting WHERE setting_key = 'MEMORY_COLLECTIONS'"))
                .as("기존 줄을 덮어쓰지 않는다")
                .containsExactly("{}");
            assertThat(strings(statement, "SELECT COUNT(*) FROM memory_collection")).containsExactly("1");
        }
    }


    private static void insertDefaults(Statement statement, long groupId) throws SQLException {
        int order = 1;
        for (MemoryCollection collection : MemoryCollection.DEFAULTS) {
            statement.executeUpdate(String.format("""
                    INSERT INTO memory_collection (group_id, collection_key, display_name, sort_order, created_at)
                    VALUES (%d, '%s', '%s', %d, CURRENT_TIMESTAMP(6))
                    """, groupId, collection.key(), collection.displayName(), order++));
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


    private static List<MemoryCollection> collectionsOf(Statement statement, long groupId) throws SQLException {
        try (ResultSet rows = statement.executeQuery(
            "SELECT setting_value FROM group_setting WHERE setting_key = 'MEMORY_COLLECTIONS' AND group_id = " + groupId)) {
            assertThat(rows.next()).as("%d번 그룹의 MEMORY_COLLECTIONS 줄", groupId).isTrue();
            return MemoryCollectionService.decode(rows.getString(1));
        }
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
