package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 같은 왕복과 경쟁 시험을 새 DB에 적용한 실제 Flyway 스키마와 validate에서 실행한다. */
@Tag("mysql")
class ConnectorGuardBindingMysqlRoundTripTest extends ConnectorGuardBindingRoundTripTest {
    private static final MysqlTestDatabase DATABASE = MysqlTestDatabase.create();


    @DynamicPropertySource
    static void configureMysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
