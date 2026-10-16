package com.bifos.assistant.context.eval;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * H2 에서 확인한 운영 집계 쿼리를 Flyway 로 만든 실제 MySQL 스키마에서도 돌린다. 운영에서 돌 쿼리라 MySQL 이 거절하는 문법이 없어야 한다.
 *
 * <p>저장소 인터페이스 밖의 쿼리라 {@code RepositoryQueryMysqlTest} 가 찾지 못하므로 이 검사가 그 몫을 한다.
 */
@Tag("mysql")
class MemoryOpsMetricsSqlMysqlTest extends MemoryOpsMetricsSqlTest {

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
}
