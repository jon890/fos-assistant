package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.AgentIntroduction;
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
 * 첫 에이전트의 이름을 「비서」 로 바꾸는 마이그레이션을 본다(docs/features/agent-team/spec.md 의 DECISION5).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로 운영과 같은 Flyway 스키마를 여기서 따로 만든다.
 */
class AgentPrimaryNameMigrationTest {

    /** 이름을 바꾸는 마이그레이션 바로 앞 버전이다. 소개 칸을 더한 버전이다. */
    private static final String BEFORE = "20261105074406";

    /** 이름을 바꾸는 마이그레이션이다. */
    private static final String RENAMED = "20261105074407";


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:agent-primary-name-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("표시 이름 그대로이고 주인이 그 사람인 첫 에이전트만 비서로 바꾸고 소개를 채운다")
    void renamesOnlyUntouchedFirstAgents() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (id, email, display_name, group_id, role, created_at) VALUES
                        (1, 'mom@example.test', '엄마', 1, 'ADMIN', CURRENT_TIMESTAMP(6)),
                        (2, 'kid@example.test', '아이', 1, 'MEMBER', CURRENT_TIMESTAMP(6)),
                        (3, 'dad@example.test', '아빠', 1, 'MEMBER', CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO allowed_person (email, display_name, hermes_profile, enabled, created_at) VALUES
                        ('mom@example.test', '엄마', 'mom', TRUE, CURRENT_TIMESTAMP(6)),
                        ('kid@example.test', '아이', 'kid', TRUE, CURRENT_TIMESTAMP(6)),
                        ('dad@example.test', '아빠', 'dad', TRUE, CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode, credential_scope,
                        visibility, owner_user_id, enabled, created_at, description) VALUES
                        (11, 'mom', '엄마', 'mom', 'http://runtime.test/mom', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD',
                            'PRIVATE', 1, TRUE, CURRENT_TIMESTAMP(6), NULL),
                        (12, 'kid', '숙제 친구', 'kid', 'http://runtime.test/kid', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD',
                            'PRIVATE', 2, TRUE, CURRENT_TIMESTAMP(6), NULL),
                        (13, 'dad', '아빠', 'dad', 'http://runtime.test/dad', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD',
                            'PRIVATE', 1, TRUE, CURRENT_TIMESTAMP(6), NULL),
                        (14, 'other', '엄마', 'other', 'http://runtime.test/other', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD',
                            'PRIVATE', 1, TRUE, CURRENT_TIMESTAMP(6), '이미 쓴 소개')
                    """);
        }

        migrate(database, RENAMED);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT name FROM agent ORDER BY id"))
                .as("이름을 바꾼 에이전트, 주인을 바꾼 에이전트, 기본 profile 이 아닌 에이전트는 그대로다")
                .containsExactly(AgentIntroduction.PRIMARY_NAME, "숙제 친구", "아빠", "엄마");
            assertThat(strings(statement, "SELECT description FROM agent WHERE id = 11"))
                .containsExactly(AgentIntroduction.PRIMARY_DESCRIPTION);
            assertThat(strings(statement, "SELECT COUNT(*) FROM agent WHERE description IS NOT NULL")).containsExactly("2");
            assertThat(strings(statement, "SELECT COUNT(*) FROM agent WHERE updated_at IS NOT NULL"))
                .as("사람이 저장한 것이 아니어서 updated_at 은 바꾸지 않는다")
                .containsExactly("0");
        }
    }


    @Test
    @DisplayName("지운 첫 에이전트는 바꾸지 않는다")
    void skipsDeletedAgents() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (id, email, display_name, group_id, role, created_at)
                    VALUES (1, 'mom@example.test', '엄마', 1, 'ADMIN', CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO allowed_person (email, display_name, hermes_profile, enabled, created_at)
                    VALUES ('mom@example.test', '엄마', 'mom', TRUE, CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode, credential_scope,
                        visibility, owner_user_id, enabled, created_at, deleted_at)
                    VALUES (11, 'mom', '엄마', 'mom', 'http://runtime.test/mom', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD',
                        'PRIVATE', 1, FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """);
        }

        migrate(database, RENAMED);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT name FROM agent")).containsExactly("엄마");
        }
    }


    private static void migrate(Database database, String target) {
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .target(target)
            .load()
            .migrate();
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
