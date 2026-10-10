package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.AttachmentCleaner;
import com.bifos.assistant.chat.application.FlowRegistry;
import com.bifos.assistant.proactive.application.ValueEvaluationRecovery;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 정렬 규칙을 통일하기 전 스키마(V57)에서 저장소 쿼리 검사가 정렬 규칙이 섞인 쿼리를 오류 1267 로 잡는다는 것을 단언한다.
 *
 * <p>이 증거가 없으면 {@link RepositoryQueryMysqlTest} 가 통과하는 것이 쿼리가 맞아서인지 검사가 보지 못해서인지 알 수 없다. 이
 * 검사가 실패하면 그 검사가 이 종류의 결함을 더는 잡지 못한다는 뜻이다.
 *
 * <p>V57 뒤의 마이그레이션이 표와 칸을 더하므로 V57 스키마는 지금의 엔티티 검증을 통과하지 못한다. 그래서 {@code ddl-auto} 를
 * {@code none} 으로 두어 검증 없이 문맥을 띄운다. 그 결과 V57 뒤에 생긴 표의 쿼리는 없는 표 오류로 실패한다. 그 실패는 이 검사가
 * 보려는 것이 아니므로 실패 전체를 단언하지 않고, 정렬 규칙이 섞인 두 메서드가 실패 목록에 있고 그 실패가 오류 1267 인지만 본다.
 *
 * <p>같은 까닭으로 기동할 때 에이전트 표를 읽는 {@link FlowRegistry} 를 대역으로 둔다. V57 뒤에 {@code agent} 에 더한 칸이 있어
 * 그 읽기가 없는 칸 오류로 문맥을 띄우지 못한다.
 */
@Tag("mysql")
@SpringBootTest
@ActiveProfiles("test")
class CollationMixQueryMysqlTest {

    private static final String BEFORE_VERSION = "57";
    private static final String OLD_COLLATION = "utf8mb4_unicode_ci";
    private static final String NEW_COLLATION = "utf8mb4_0900_ai_ci";
    private static final int ILLEGAL_MIX_OF_COLLATIONS = 1267;

    /** V57 스키마에서 정렬 규칙이 다른 두 표의 문자열 칸을 비교하는 저장소 메서드다. */
    private static final List<String> COLLATION_MIXED_METHODS = List.of(
            "ExecutionEventRepository.findUnscheduledChildren", "ExecutionEventRepository.countUnscheduledChildren");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    /** 기동할 때의 흐름 이름 확인을 건너뛴다. 까닭은 클래스 설명에 있다. */
    @MockitoBean
    private FlowRegistry flowRegistry;

    /** V57 에는 가치 평가 표가 없어 현재 실행의 기동 복구를 실행하지 않는다. */
    @MockitoBean
    private ValueEvaluationRecovery valueEvaluationRecovery;

    /** V57에는 삭제 요청 칸이 없으므로 현재 첨부의 기동 복구는 실행하지 않는다. */
    @MockitoBean
    private AttachmentCleaner attachmentCleaner;

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
                .contains(COLLATION_MIXED_METHODS.toArray(String[]::new));
        // V57 뒤에 생긴 표의 쿼리는 없는 표 오류로 실패하므로 정렬 규칙이 섞인 두 메서드의 실패만 오류 코드를 본다.
        assertThat(failures)
                .filteredOn(failure -> COLLATION_MIXED_METHODS.contains(failure.method()))
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
