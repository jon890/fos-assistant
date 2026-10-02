package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import com.bifos.assistant.testsupport.RepositoryQuerySweep;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 정렬 규칙을 통일하기 전 스키마(V57)에서 저장소 쿼리 검사가 정렬 규칙이 섞인 쿼리를 오류 1267 로 잡는다는 것을 단언한다.
 *
 * <p>이 증거가 없으면 {@link RepositoryQueryMysqlTest} 가 통과하는 것이 쿼리가 맞아서인지 검사가 보지 못해서인지 알 수 없다. 이
 * 검사가 실패하면 그 검사가 이 종류의 결함을 더는 잡지 못한다는 뜻이다.
 *
 * <p>V58 부터 V65 까지는 정렬 규칙만 바꾸므로 V57 스키마도 지금의 엔티티로 {@code ddl-auto=validate} 를 통과한다. 그 뒤에 칸을
 * 더하는 마이그레이션이 생기면 V57 스키마가 엔티티 검증을 통과하지 못해 이 문맥이 뜨지 않는다. 그때는 이 클래스의 {@code ddl-auto} 를
 * {@code none} 으로 바꾼다. 없는 칸 때문에 실패하는 메서드가 생기면 「실패는 모두 1267」 단언을 빼고 두 메서드의 단언만 남긴다.
 */
@Tag("mysql")
@SpringBootTest
@ActiveProfiles("test")
class CollationMixQueryMysqlTest {

    private static final String BEFORE_VERSION = "57";
    private static final String OLD_COLLATION = "utf8mb4_unicode_ci";
    private static final String NEW_COLLATION = "utf8mb4_0900_ai_ci";
    private static final int ILLEGAL_MIX_OF_COLLATIONS = 1267;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void useMysqlBeforeUnify(DynamicPropertyRegistry registry) {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.target", () -> BEFORE_VERSION);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    @DisplayName("통일하기 전 스키마까지만 올라갔다")
    void stopsBeforeCollationUnify() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(BEFORE_VERSION);
        assertThat(tableCollation("execution_event")).isEqualTo(OLD_COLLATION);
        assertThat(tableCollation("agent_execution")).isEqualTo(NEW_COLLATION);
    }

    @Test
    @DisplayName("저장소 쿼리 검사가 정렬 규칙이 섞인 쿼리를 잡는다")
    void catchesCollationMix() {
        List<RepositoryQuerySweep.Failure> failures = new RepositoryQuerySweep(context, transactionManager)
                .run(Set.of())
                .failures();

        assertThat(failures)
                .extracting(RepositoryQuerySweep.Failure::method)
                .contains(
                        "ExecutionEventRepository.findUnscheduledChildren",
                        "ExecutionEventRepository.countUnscheduledChildren");
        assertThat(failures)
                .allSatisfy(failure -> assertThat(sqlErrorCode(failure.cause()))
                        .as("%s 의 SQL 오류 코드. 원인: %s", failure.method(), failure.cause())
                        .isEqualTo(ILLEGAL_MIX_OF_COLLATIONS));
    }

    private String tableCollation(String table) {
        return jdbc.queryForObject(
                "SELECT table_collation FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                String.class,
                table);
    }

    /** 원인 사슬에서 처음 만나는 {@link SQLException} 의 오류 코드다. 없으면 null 이다. */
    private static Integer sqlErrorCode(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                return sql.getErrorCode();
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }
}
