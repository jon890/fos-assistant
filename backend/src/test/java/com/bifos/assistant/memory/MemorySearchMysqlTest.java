package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.testsupport.MemorySearchSqlProbe;
import com.bifos.assistant.testsupport.MysqlTestDatabase;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** H2で見た検索の意味とSQL投影をFlywayの実際のMySQLスキーマでも確認する。 */
@Tag("mysql")
class MemorySearchMysqlTest extends MemorySearchTest {
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
    @DisplayName("검색 statement는 바깥 트랜잭션의 30초 제한과 분리해 2초에 지연을 중단하고 실패를 돌려준다")
    void cancelsDelayedSearchWithinItsOwnTimeout() {
        save("합성 지연");
        MemorySearchSqlProbe.capture();
        MemorySearchSqlProbe.delay();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setTimeout(30);
        long started = System.nanoTime();
        assertThatThrownBy(() -> outer.execute(status -> memories.searchFor(USER, CORE, "합성", 10, null)))
                .isInstanceOf(RuntimeException.class);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsed).isBetween(800L, 4500L);
        assertProjectionAndTimeout();
        System.out.printf(
                "memory-search timeoutMs=%d jdbcTimeoutSeconds=%d%n", elapsed, MemorySearchSqlProbe.timeout());
    }

    @Test
    @DisplayName("검색 SQL 오류를 빈 결과로 바꾸지 않는다")
    void propagatesSqlFailure() {
        save("합성 오류");
        MemorySearchSqlProbe.capture();
        MemorySearchSqlProbe.invalidate();
        assertThatThrownBy(() -> memories.searchFor(USER, CORE, "합성", 10, null)).isInstanceOf(RuntimeException.class);
        assertProjectionAndTimeout();
    }
}
