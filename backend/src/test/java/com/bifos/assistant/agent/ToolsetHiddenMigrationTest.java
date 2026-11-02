package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.ToolsetVisibilityService;
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
 * 그룹이 숨긴 toolset 을 {@code toolset_hidden} 에서 {@code group_setting} 의 {@code TOOLSET} 줄로 옮기는 마이그레이션을
 * 본다(ADR-20261102 / table-reduce-r2).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로 운영과 같은 Flyway 스키마는 여기서 따로 만든다. 옮긴 JSON 은 Control
 * Plane 이 쓰는 {@link ToolsetVisibilityService} 의 해석으로 읽어 두 쪽의 모양이 같은지도 함께 본다.
 */
class ToolsetHiddenMigrationTest {

    /** 옮기는 마이그레이션 바로 앞 버전이다. */
    private static final String BEFORE = "20261102053007";


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:toolset-hidden-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("그룹마다 숨긴 이름이 이름 순에 중복 없이 TOOLSET 줄 하나로 옮겨지고 숨긴 것이 없던 그룹은 줄이 없다")
    void movesHiddenNamesIntoOneRowPerGroup() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO toolset_hidden (group_id, name) VALUES
                        (1, 'spotify'), (1, 'discord'), (1, 'web'), (2, 'terminal')
                    """);
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT group_id FROM group_setting WHERE setting_key = 'TOOLSET' ORDER BY group_id"))
                .containsExactly("1", "2");
            assertThat(hiddenOf(statement, 1)).containsExactly("discord", "spotify", "web");
            assertThat(hiddenOf(statement, 2)).containsExactly("terminal");
            assertThat(strings(statement, "SELECT COUNT(*) FROM group_setting WHERE setting_key <> 'TOOLSET'"))
                .as("MODEL 줄은 만들지 않는다")
                .containsExactly("0");
            assertThat(strings(statement, "SELECT COUNT(*) FROM toolset_hidden"))
                .as("옛 표는 직전 이미지로 되돌릴 수 있게 지우지 않는다")
                .containsExactly("4");
        }
    }


    @Test
    @DisplayName("숨긴 toolset 이 없으면 줄을 만들지 않는다")
    void createsNothingWithoutHiddenRows() throws SQLException {
        Database database = createDatabase();
        migrate(database, null);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT COUNT(*) FROM group_setting")).containsExactly("0");
        }
    }


    @Test
    @DisplayName("같은 그룹의 TOOLSET 줄이 이미 있으면 덮어쓰지 않고 멈춘다")
    void refusesToOverwriteExistingToolsetRow() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO toolset_hidden (group_id, name) VALUES (1, 'spotify')");
            statement.executeUpdate(
                "INSERT INTO group_setting (group_id, setting_key, setting_value) VALUES (1, 'TOOLSET', '{}')");
        }

        assertThatThrownBy(() -> migrate(database, null))
            .isInstanceOf(RuntimeException.class)
            .hasRootCauseInstanceOf(SQLException.class);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT setting_value FROM group_setting WHERE setting_key = 'TOOLSET'"))
                .as("기존 줄을 덮어쓰지 않고 다른 TOOLSET 줄도 만들지 않는다")
                .containsExactly("{}");
            assertThat(strings(statement, "SELECT COUNT(*) FROM toolset_hidden")).containsExactly("1");
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


    private static List<String> hiddenOf(Statement statement, long groupId) throws SQLException {
        try (ResultSet rows = statement.executeQuery(
            "SELECT setting_value FROM group_setting WHERE setting_key = 'TOOLSET' AND group_id = " + groupId)) {
            assertThat(rows.next()).as("%d번 그룹의 TOOLSET 줄", groupId).isTrue();
            return ToolsetVisibilityService.decode(rows.getString(1));
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
