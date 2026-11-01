package com.bifos.assistant.chat.modelchoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 에이전트 기본 모델과 대화의 직접 고른 모델을 없애는 세 마이그레이션을 본다(ADR-20261101 / model-setting).
 *
 * <p>대화의 {@code DEFAULT} 선택을 선택 없음으로 바꾸는 파일과 {@code agent} 의 세 컬럼을 지우는 파일은 따로다. 삭제만
 * 미룰 수 있어야 해서, 앞 파일까지만 적용했을 때 컬럼이 남는지도 함께 본다. 세 번째 파일은 대화의 {@code CUSTOM}
 * 선택을 선택 없음으로 옮기고 그 대화의 칸, provider, 모델, effort 를 비운다.
 */
class AgentModelDefaultDropMigrationTest {

    /** 이 두 파일이 들어오기 전 마지막 버전이다. */
    private static final String BEFORE = "20261031171503";

    private static final String CLEAR_MODE = "20261031171509";

    /** {@code CUSTOM} 선택을 비우는 파일 바로 앞 버전이다. */
    private static final String BEFORE_CUSTOM_CLEAR = "20261031171513";

    private Database database;


    @BeforeEach
    void setUp() throws SQLException {
        database = createDatabase();
        migrate(BEFORE);
        seed();
    }


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:agent-model-default-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("CUSTOM 을 비우는 파일 앞까지는 DEFAULT 대화만 선택 없음으로 바뀌고 TIER 와 CUSTOM 대화와 선택 없는 대화는 그대로다")
    void clearsOnlyDefaultSelection() throws SQLException {
        migrate(BEFORE_CUSTOM_CLEAR);

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT model_selection_mode FROM conversation WHERE id = 31"))
                .as("DEFAULT 대화의 모드")
                .containsExactly((String)null);
            assertThat(strings(statement, "SELECT model_tier FROM conversation WHERE id = 31"))
                .as("DEFAULT 대화의 단계")
                .containsExactly((String)null);
            assertThat(strings(statement, "SELECT model_selection_mode FROM conversation WHERE id = 32"))
                .containsExactly("TIER");
            assertThat(strings(statement, "SELECT model_tier FROM conversation WHERE id = 32"))
                .containsExactly("HIGH");
            assertThat(strings(statement, "SELECT model_selection_mode FROM conversation WHERE id = 33"))
                .containsExactly("CUSTOM");
            assertThat(strings(statement, "SELECT model FROM conversation WHERE id = 33"))
                .containsExactly("example-model");
            assertThat(strings(statement, "SELECT model_selection_mode FROM conversation WHERE id = 34"))
                .containsExactly((String)null);
        }
    }


    @Test
    @DisplayName("CUSTOM 대화는 선택 없음으로 바뀌고 칸과 provider 모델 effort 가 모두 비며 TIER 대화와 선택 없는 대화는 그대로다")
    void clearsCustomSelectionAndItsValues() throws SQLException {
        migrate(null);

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String column : List.of(
                "model_selection_mode", "model_tier", "model_provider", "model", "reasoning_effort")) {
                assertThat(strings(statement, "SELECT " + column + " FROM conversation WHERE id = 33"))
                    .as("CUSTOM 대화의 %s", column)
                    .containsExactly((String)null);
            }
            assertThat(strings(statement, "SELECT model_selection_mode FROM conversation WHERE id = 32"))
                .containsExactly("TIER");
            assertThat(strings(statement, "SELECT model_tier FROM conversation WHERE id = 32"))
                .containsExactly("HIGH");
            assertThat(strings(statement, "SELECT model_selection_mode FROM conversation WHERE id = 34"))
                .containsExactly((String)null);
            assertThat(strings(statement, "SELECT model_tier FROM conversation WHERE id = 34"))
                .containsExactly((String)null);
        }
    }


    @Test
    @DisplayName("에이전트의 기본 모델 컬럼 셋은 지워지고 에이전트 줄과 다른 컬럼은 남는다")
    void dropsAgentModelColumnsAndKeepsTheRow() throws SQLException {
        migrate(null);

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(columnExists(connection, "agent", "default_model_provider")).isFalse();
            assertThat(columnExists(connection, "agent", "default_model")).isFalse();
            assertThat(columnExists(connection, "agent", "default_reasoning_effort")).isFalse();
            assertThat(columnExists(connection, "agent", "hermes_profile")).isTrue();
            assertThat(strings(statement, "SELECT hermes_profile FROM agent WHERE id = 21")).containsExactly("dad");
        }
    }


    @Test
    @DisplayName("컬럼 삭제 파일만 빼면 DEFAULT 이관만 적용되고 에이전트 컬럼은 그대로 남는다")
    void clearingDefaultSelectionDoesNotDropColumns() throws SQLException {
        migrate(CLEAR_MODE);

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT model_selection_mode FROM conversation WHERE id = 31"))
                .containsExactly((String)null);
            assertThat(columnExists(connection, "agent", "default_model")).isTrue();
            assertThat(strings(statement, "SELECT default_model FROM agent WHERE id = 21"))
                .containsExactly("example-agent-model");
        }
    }


    private void migrate(String target) {
        var configuration = Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    private void seed() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (id, email, display_name, group_id, role, created_at) VALUES
                        (11, 'a@example.com', 'A', 1, 'ADMIN', CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,
                        credential_scope, visibility, enabled, created_at,
                        default_model_provider, default_model, default_reasoning_effort)
                    VALUES (21, 'dad', 'Dad', 'dad', 'http://runtime.test/p/dad', 'SUBSCRIPTION',
                        'SHARED_HOUSEHOLD', 'GROUP', TRUE, CURRENT_TIMESTAMP(6),
                        'example-provider', 'example-agent-model', 'medium')
                    """);
            statement.executeUpdate("""
                    INSERT INTO conversation (id, public_id, user_id, agent_id, title, created_at, updated_at,
                        model_selection_mode, model_tier, model_provider, model, reasoning_effort) VALUES
                        (31, X'00000000000000000000000000000031', 11, 21, 'default', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),
                            'DEFAULT', NULL, NULL, NULL, NULL),
                        (32, X'00000000000000000000000000000032', 11, 21, 'tier', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),
                            'TIER', 'HIGH', NULL, NULL, NULL),
                        (33, X'00000000000000000000000000000033', 11, 21, 'custom', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),
                            'CUSTOM', NULL, 'example-provider', 'example-model', 'high'),
                        (34, X'00000000000000000000000000000034', 11, 21, 'none', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),
                            NULL, NULL, NULL, NULL, NULL)
                    """);
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


    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        boolean upper = connection.getMetaData().storesUpperCaseIdentifiers();
        try (ResultSet columns = connection.getMetaData()
            .getColumns(connection.getCatalog(), null, upper ? table.toUpperCase(Locale.ROOT) : table, null)) {
            while (columns.next()) {
                if (columns.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return true;
                }
            }
            return false;
        }
    }


    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
