package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 같은 내부 호출과 source Python·SDK 왕복을 실제 MySQL에서 검증한다. */
@Tag("mysql")
class FinancialConnectorProducerMysqlRoundTripTest extends FinancialConnectorProducerRoundTripTest {
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
