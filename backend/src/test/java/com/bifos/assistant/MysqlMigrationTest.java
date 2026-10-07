package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    @DisplayName("높은 시각 버전 적용 뒤 늦게 들어온 낮은 시각 버전도 실제 MySQL 에 적용한다")
    void appliesLateTimestampMigration(@TempDir Path directory) throws IOException, SQLException {
        assertThat(flyway.getConfiguration().isOutOfOrder()).isTrue();
        MysqlTestDatabase database = MysqlTestDatabase.create();
        Path later = directory.resolve("V20261007000002__later.sql");
        Path earlier = directory.resolve("V20261007000001__earlier.sql");
        try {
            Files.writeString(later, """
                    CREATE TABLE timestamp_later (id INT PRIMARY KEY)
                    ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
                    """);
            Flyway probe = Flyway.configure()
                    .configuration(flyway.getConfiguration())
                    .dataSource(database.url(), database.username(), database.password())
                    .locations("classpath:db/migration", "filesystem:" + directory)
                    .load();
            probe.migrate();
            assertThat(probe.info().current().getVersion().getVersion()).isEqualTo("20261007000002");

            // SQL 은 서로 독립이다. 두 번째 호출 때 낮은 버전이 처음 보이게 한다.
            Files.writeString(earlier, """
                    CREATE TABLE timestamp_earlier (id INT PRIMARY KEY)
                    ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
                    """);
            assertThat(probe.migrate().migrationsExecuted).isEqualTo(1);
            probe.validate();
            assertThat(probe.info().pending()).isEmpty();
            try (var connection = DriverManager.getConnection(database.url(), database.username(), database.password());
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM timestamp_earlier")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        } finally {
            Files.deleteIfExists(earlier);
            Files.deleteIfExists(later);
        }
    }

    @Test
    @DisplayName("모든 표와 문자열 칸의 정렬 규칙이 하나다")
    void keepsSingleCollation() {
        assertThat(jdbc.queryForObject("SELECT @@collation_server", String.class))
                .as("서버 기본 정렬 규칙이 운영과 다르면 이 검사가 마이그레이션을 확인하지 못한다")
                .isEqualTo(SERVER_COLLATION);

        List<String> columns = jdbc.queryForList("""
                SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND collation_name IS NOT NULL
                  AND table_name <> 'flyway_schema_history' AND collation_name <> ?
                ORDER BY table_name, ordinal_position
                """, String.class, DEFAULT_COLLATION);
        assertThat(columns).as("정렬 규칙이 %s 가 아닌 문자열 칸", DEFAULT_COLLATION).isEmpty();

        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history' AND table_collation <> ?
                ORDER BY table_name
                """, String.class, DEFAULT_COLLATION);
        assertThat(tables).as("정렬 규칙이 %s 가 아닌 표", DEFAULT_COLLATION).isEmpty();
    }
}
