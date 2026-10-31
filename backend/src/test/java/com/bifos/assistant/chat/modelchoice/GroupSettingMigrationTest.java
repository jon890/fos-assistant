package com.bifos.assistant.chat.modelchoice;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.modelchoice.application.ModelSettingStore;
import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelSetting;
import com.bifos.assistant.model.domain.type.ModelTier;
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
 * 그룹 모델 설정 셋을 {@code group_setting} 하나로 합치고 옛 단계 코드를 다섯 칸으로 옮기는 마이그레이션을 본다
 * (ADR-20261101 / model-setting).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로 운영과 같은 Flyway 스키마는 여기서 따로 만든다. 옮긴 JSON 은 Control
 * Plane 이 쓰는 {@link ModelSettingStore} 의 해석으로 읽어, 두 쪽의 모양이 같은지도 함께 본다.
 */
class GroupSettingMigrationTest {

    /** 옛 표 셋이 있는 버전이다. 이 뒤에 들어온 다른 마이그레이션은 줄을 넣은 다음 함께 적용된다. */
    private static final String BEFORE = "20261016224032";

    private Database database;


    @BeforeEach
    void setUp() throws SQLException {
        database = createDatabase();
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .target(BEFORE)
            .load()
            .migrate();
        seed();
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:group-setting-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("정의와 기본 단계와 숨김이 있던 그룹은 MODEL 줄 하나로 옮겨지고 옛 단계는 가장 가까운 칸으로 간다")
    void movesDefinitionsDefaultAndHiddenIntoOneRow() throws SQLException {
        ModelSetting setting = modelSetting(1);

        assertThat(setting.tiers()).extracting(ModelSetting.Stop::tier).containsExactly(ModelTier.values());
        assertThat(setting.stop(ModelTier.INSTANT))
            .isEqualTo(new ModelSetting.Stop(ModelTier.INSTANT, "provider-a", "model-fast", "low"));
        assertThat(setting.stop(ModelTier.MEDIUM))
            .as("provider 를 비운 옛 정의는 비운 채 옮긴다")
            .isEqualTo(new ModelSetting.Stop(ModelTier.MEDIUM, null, "model-balanced", "medium"));
        assertThat(setting.stop(ModelTier.HIGH))
            .isEqualTo(new ModelSetting.Stop(ModelTier.HIGH, "provider-a", "model-deep", "high"));
        assertThat(setting.stop(ModelTier.EXTRA_HIGH).isEmpty()).as("새 칸은 빈 칸으로 시작한다").isTrue();
        assertThat(setting.stop(ModelTier.PRO).isEmpty()).as("새 칸은 빈 칸으로 시작한다").isTrue();
        assertThat(setting.defaultTier()).isEqualTo(ModelTier.MEDIUM);
        assertThat(setting.hidden().entries()).containsExactly(
            new HiddenModels.Entry("provider-a", "model-old"),
            new HiddenModels.Entry("provider-b", null));
    }


    @Test
    @DisplayName("정의 없이 숨김만 있던 그룹은 다섯 칸이 빈 채 숨김만 옮기고 기본 단계만 있던 그룹은 기본 칸만 옮긴다")
    void movesPartialGroups() throws SQLException {
        ModelSetting hiddenOnly = modelSetting(2);
        assertThat(hiddenOnly.tiers()).allMatch(ModelSetting.Stop::isEmpty);
        assertThat(hiddenOnly.defaultTier()).isNull();
        assertThat(hiddenOnly.hidden().entries()).containsExactly(new HiddenModels.Entry("provider-c", "model-x"));

        ModelSetting defaultOnly = modelSetting(3);
        assertThat(defaultOnly.tiers()).allMatch(ModelSetting.Stop::isEmpty);
        assertThat(defaultOnly.defaultTier()).isEqualTo(ModelTier.HIGH);
        assertThat(defaultOnly.hidden().isEmpty()).isTrue();
    }


    @Test
    @DisplayName("옮길 것이 없던 그룹은 설정 줄을 두지 않고 설정 줄은 옮긴 그룹 수만큼이다")
    void dropsEmptyGroups() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(longs(statement, "SELECT group_id FROM group_setting ORDER BY group_id"))
                .as("4번 그룹은 기본 단계가 비어 있던 줄뿐이다")
                .containsExactly(1L, 2L, 3L);
            assertThat(longs(statement, "SELECT COUNT(*) FROM group_setting WHERE setting_key <> 'MODEL'"))
                .containsExactly(0L);
        }
    }


    @Test
    @DisplayName("사용자 기본 단계와 대화와 예약 작업의 옛 단계 코드는 새 칸 코드로 바뀌고 실행 기록은 그대로다")
    void renamesStoredTierCodes() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT model_default_tier FROM app_user ORDER BY id"))
                .containsExactly("INSTANT", "MEDIUM", "HIGH", null);
            assertThat(strings(statement, "SELECT model_tier FROM conversation ORDER BY id"))
                .containsExactly("HIGH", null);
            assertThat(strings(statement, "SELECT model_tier FROM task ORDER BY id"))
                .containsExactly("MEDIUM", null);
            assertThat(strings(statement, "SELECT model_tier FROM agent_execution ORDER BY id"))
                .as("실행 기록은 그때 고른 값의 기록이다")
                .containsExactly("DEEP");
        }
    }


    @Test
    @DisplayName("옮긴 뒤 옛 표 둘과 기본 단계 칸이 없어져 표 수가 늘지 않는다")
    void dropsOldTables() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(tableExists(connection, "model_tier_definition")).isFalse();
            assertThat(tableExists(connection, "model_hidden")).isFalse();
            assertThat(tableExists(connection, "model_tier_group_setting")).isFalse();
            assertThat(tableExists(connection, "group_setting")).isTrue();
            try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table(connection, "group_setting"), null)) {
                List<String> names = new ArrayList<>();
                while (columns.next()) {
                    names.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
                assertThat(names).containsExactlyInAnyOrder("group_id", "setting_key", "setting_value", "updated_at");
            }
        }
    }


    private void seed() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (id, email, display_name, group_id, role, created_at, model_default_tier) VALUES
                        (11, 'a@example.com', 'A', 1, 'ADMIN', CURRENT_TIMESTAMP(6), 'FAST'),
                        (12, 'b@example.com', 'B', 1, 'MEMBER', CURRENT_TIMESTAMP(6), 'BALANCED'),
                        (13, 'c@example.com', 'C', 2, 'ADMIN', CURRENT_TIMESTAMP(6), 'DEEP'),
                        (14, 'd@example.com', 'D', 3, 'ADMIN', CURRENT_TIMESTAMP(6), NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,
                        credential_scope, visibility, enabled, created_at)
                    VALUES (21, 'dad', 'Dad', 'dad', 'http://runtime.test/p/dad', 'SUBSCRIPTION',
                        'SHARED_HOUSEHOLD', 'GROUP', TRUE, CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO conversation (id, public_id, user_id, agent_id, title, created_at, updated_at,
                        model_selection_mode, model_tier) VALUES
                        (31, X'00000000000000000000000000000031', 11, 21, 'tier', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),
                            'TIER', 'DEEP'),
                        (32, X'00000000000000000000000000000032', 11, 21, 'none', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),
                            NULL, NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO task (id, public_id, owner_user_id, agent_id, title, instruction, state,
                        conversation_mode, notify, created_at, updated_at, model_tier,
                        trigger_type, cron_expr, time_zone, missed_policy) VALUES
                        (41, X'00000000000000000000000000000041', 11, 21, 't', 'i', 'ACTIVE', 'SINGLE', 'NEVER',
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'BALANCED', 'CRON', '0 9 * * *', 'Asia/Seoul', 'SKIP'),
                        (42, X'00000000000000000000000000000042', 11, 21, 't', 'i', 'ACTIVE', 'SINGLE', 'NEVER',
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), NULL, 'CRON', '0 9 * * *', 'Asia/Seoul', 'SKIP')
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent_execution (id, user_id, conversation_id, profile_name, cost_mode, status,
                        started_at, model_tier)
                    VALUES (51, 11, 31, 'dad', 'SUBSCRIPTION', 'SUCCEEDED', CURRENT_TIMESTAMP(6), 'DEEP')
                    """);
            statement.executeUpdate("""
                    INSERT INTO model_tier_definition (group_id, tier, provider, model, reasoning_effort) VALUES
                        (1, 'FAST', 'provider-a', 'model-fast', 'low'),
                        (1, 'BALANCED', NULL, 'model-balanced', 'medium'),
                        (1, 'DEEP', 'provider-a', 'model-deep', 'high')
                    """);
            statement.executeUpdate("""
                    INSERT INTO model_tier_group_setting (group_id, default_tier) VALUES
                        (1, 'BALANCED'), (3, 'DEEP'), (4, NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO model_hidden (group_id, provider, model) VALUES
                        (1, 'provider-b', ''), (1, 'provider-a', 'model-old'), (2, 'provider-c', 'model-x')
                    """);
        }
    }


    private ModelSetting modelSetting(long groupId) throws SQLException {
        try (Connection connection = connect();
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT setting_value FROM group_setting WHERE setting_key = 'MODEL' AND group_id = " + groupId)) {
            assertThat(rows.next()).as("%d번 그룹의 MODEL 줄", groupId).isTrue();
            return ModelSettingStore.decode(rows.getString(1));
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


    private static List<Long> longs(Statement statement, String query) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                values.add(rows.getLong(1));
            }
        }
        return values;
    }


    private static boolean tableExists(Connection connection, String name) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, table(connection, name), null)) {
            while (tables.next()) {
                if (tables.getString("TABLE_NAME").equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }
    }


    /** H2 는 이름을 대문자로 갖는다. 같은 서버의 다른 데이터베이스를 읽지 않게 지금 catalog 로 좁힌다. */
    private static String table(Connection connection, String name) throws SQLException {
        return connection.getMetaData().storesUpperCaseIdentifiers() ? name.toUpperCase(Locale.ROOT) : name;
    }


    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
