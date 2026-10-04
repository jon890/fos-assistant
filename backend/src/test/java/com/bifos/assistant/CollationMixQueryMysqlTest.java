package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.infra.ResultDeliveryAttemptRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryItemRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryRepository;
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
 * <p>V57 스키마에는 그 뒤에 생긴 표가 없어 지금의 엔티티로 검증하면 문맥이 뜨지 않는다. 그래서 {@code ddl-auto} 를
 * {@code none} 으로 둔다. V57 뒤에 생긴 표의 저장소는 {@link #TABLES_AFTER_BEFORE_VERSION} 에 둔다. 그 저장소의 실패는 표가 없다는
 * 오류 1146 이어야 하고, 나머지 실패는 모두 1267 이어야 한다. 그 저장소에 메서드를 더해도 이 검사를 고치지 않는다.
 * V57 뒤에 표를 새로 만들면 그 저장소를 목록에 더한다. 있던 표에 칸을 더해 실패하는 메서드가 생기면 이 나눔으로는 부족하므로
 * 그때 다시 정한다.
 */
@Tag("mysql")
@SpringBootTest
@ActiveProfiles("test")
class CollationMixQueryMysqlTest {

    private static final String BEFORE_VERSION = "57";
    private static final String OLD_COLLATION = "utf8mb4_unicode_ci";
    private static final String NEW_COLLATION = "utf8mb4_0900_ai_ci";
    private static final int ILLEGAL_MIX_OF_COLLATIONS = 1267;
    private static final int NO_SUCH_TABLE = 1146;

    /** V57 뒤의 마이그레이션이 만든 표의 저장소다. V57 스키마에서는 그 표가 없다. */
    private static final List<Class<?>> TABLES_AFTER_BEFORE_VERSION = List.of(
            ResultDeliveryRepository.class, ResultDeliveryItemRepository.class, ResultDeliveryAttemptRepository.class);

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
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
                .filteredOn(failure -> !onTableAfterBeforeVersion(failure))
                .allSatisfy(failure -> assertThat(sqlErrorCode(failure.cause()))
                        .as("%s 의 SQL 오류 코드. 원인: %s", failure.method(), failure.cause())
                        .isEqualTo(ILLEGAL_MIX_OF_COLLATIONS));
        assertThat(failures)
                .filteredOn(CollationMixQueryMysqlTest::onTableAfterBeforeVersion)
                .allSatisfy(failure -> assertThat(sqlErrorCode(failure.cause()))
                        .as("V57 에 표가 없는 %s 의 SQL 오류 코드. 원인: %s", failure.method(), failure.cause())
                        .isEqualTo(NO_SUCH_TABLE));
    }

    /** 실패한 메서드가 V57 뒤에 생긴 표의 저장소에 있는가. 메서드 이름은 {@code 저장소.메서드} 모양이다. */
    private static boolean onTableAfterBeforeVersion(RepositoryQuerySweep.Failure failure) {
        String repository = failure.method().substring(0, failure.method().indexOf('.'));
        return TABLES_AFTER_BEFORE_VERSION.stream()
                .anyMatch(type -> type.getSimpleName().equals(repository));
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
