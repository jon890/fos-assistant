package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 운영과 같은 순서로 실제 MySQL 에서 기동한다. Flyway 가 처음부터 끝까지 마이그레이션하고 Hibernate 가 그 스키마를 검증한다.
 *
 * <p>다른 검사는 H2 에서 엔티티로 스키마를 만들어 이 길을 지나지 않는다. 문맥이 뜨면 두 단계가 모두 통과한 것이다.
 */
@Tag("mysql")
@SpringBootTest
@ActiveProfiles("test")
class MysqlMigrationTest {

    private static final String DEFAULT_COLLATION = "utf8mb4_0900_ai_ci";
    private static final String SERVER_COLLATION = "utf8mb4_unicode_ci";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void useMysql(DynamicPropertyRegistry registry) {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    @DisplayName("모든 마이그레이션이 적용되고 남은 것이 없다")
    void appliesEveryMigration() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).isNotEmpty();
    }

    @Test
    @DisplayName("운영처럼 두 정렬 규칙이 섞여 있다")
    void reproducesMixedCollations() {
        List<String> collations = jdbc.queryForList("""
                SELECT DISTINCT collation_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND collation_name IS NOT NULL
                  AND table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(collations)
                .as("서버 기본 정렬 규칙이 운영과 다르면 섞임이 재현되지 않는다")
                .containsExactlyInAnyOrder(DEFAULT_COLLATION, SERVER_COLLATION);
        assertThat(collation("agent_execution", "profile_name")).isEqualTo(DEFAULT_COLLATION);
        assertThat(collation("subagent_usage_job", "profile_name")).isEqualTo(SERVER_COLLATION);
    }

    private String collation(String table, String column) {
        return jdbc.queryForObject("""
                SELECT collation_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }
}
