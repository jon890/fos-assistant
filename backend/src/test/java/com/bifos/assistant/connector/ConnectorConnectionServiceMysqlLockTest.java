package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 설치 시작의 커밋 관측과 늦은 응답의 CAS를 실제 MySQL에서도 실행한다. */
@Tag("mysql")
class ConnectorConnectionServiceMysqlLockTest extends ConnectorConnectionServiceLockTest {
    private static MysqlTestDatabase database;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }
}
