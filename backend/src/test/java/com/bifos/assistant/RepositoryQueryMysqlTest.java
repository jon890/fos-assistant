package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.infra.MemoryQueries;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.testsupport.MysqlTestDatabase;
import com.bifos.assistant.testsupport.RepositoryQuerySweep;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.Repository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Flyway 로 만든 실제 MySQL 스키마에서 모든 저장소 쿼리를 한 번씩 실행한다.
 *
 * <p>다른 검사는 H2 에서 돌아 MySQL 만 거절하는 쿼리(정렬 규칙 섞임, 없는 함수, 예약어, 문법)를 통과시킨다. 줄이 없어도 MySQL 은
 * 그런 쿼리를 거절하므로 빈 표로 실행한다. 쿼리가 실행되는지만 보고 결과 값은 보지 않는다.
 *
 * <p>저장소 인터페이스 밖에서 만드는 쿼리는 저장소 빈에서 찾지 못하므로 이 클래스에 직접 더한다.
 */
@Tag("mysql")
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryQueryMysqlTest {

    /** 실행하지 않을 메서드다. 더할 때는 그 줄에 까닭을 주석으로 적는다. 건너뛴 메서드는 검사되지 않은 채 통과로 보인다. */
    private static final Set<String> EXCLUDED = Set.of();

    private static final String BASE_PACKAGE = "com.bifos.assistant";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MemoryRepository memoryRepository;

    private RepositoryQuerySweep.Result result;

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

    @BeforeAll
    void runEveryRepositoryMethod() {
        result = new RepositoryQuerySweep(context, transactionManager).run(EXCLUDED);
    }

    @Test
    @DisplayName("모든 저장소 메서드가 실제 MySQL 에서 실행된다")
    void executesEveryRepositoryMethod() {
        List<String> failures = result.failures().stream()
                .map(failure ->
                        failure.method() + ": " + rootCause(failure.cause()).getMessage())
                .toList();

        assertThat(failures).as("실제 MySQL 에서 실패한 저장소 메서드").isEmpty();
    }

    @Test
    @DisplayName("실행하지 못한 메서드가 없다")
    void leavesNoMethodUnexecuted() {
        assertThat(result.unsupported())
                .as("인자를 만들지 못한 메서드. RepositoryQuerySweep 에 그 타입의 값을 더한다")
                .isEmpty();
        assertThat(result.staleExclusions()).as("제외 목록에 있는데 저장소에 없는 메서드").isEmpty();
    }

    @Test
    @DisplayName("저장소를 빠뜨리지 않았다")
    void findsEveryRepository() {
        Set<String> expected = context.getBeansOfType(Repository.class).values().stream()
                .flatMap(bean -> Arrays.stream(bean.getClass().getInterfaces()))
                .filter(type -> Repository.class.isAssignableFrom(type)
                        && type.getPackageName().startsWith(BASE_PACKAGE))
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(expected).as("문맥에서 찾은 저장소 빈").isNotEmpty();
        assertThat(result.repositories()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result.executed()).contains("ExecutionEventRepository.findUnscheduledChildren");
    }

    @Test
    @DisplayName("MemoryQueries 의 조건이 실제 MySQL 에서 실행된다")
    void executesMemorySpecifications() {
        MemoryRetrieval retrieval = MemoryRetrieval.values()[0];
        List<Specification<Memory>> specifications = List.of(
                MemoryQueries.readableBy(1L, 1L),
                MemoryQueries.readableBy(1L, null),
                MemoryQueries.listedFor(1L, 1L),
                MemoryQueries.injectable(1L, 1L, retrieval, null, Set.of()),
                MemoryQueries.injectable(1L, 1L, retrieval, Set.of("core"), Set.of()),
                MemoryQueries.injectable(1L, 1L, retrieval, Set.of("core"), Set.of("core")));

        for (int i = 0; i < specifications.size(); i++) {
            Specification<Memory> specification = specifications.get(i);
            assertThatCode(() -> memoryRepository.findAll(specification))
                    .as("%d번째 조건", i + 1)
                    .doesNotThrowAnyException();
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
