package com.bifos.assistant.testsupport;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Spring 검사 컨텍스트가 {@link MysqlTestDatabase#shared()} 에 붙게 하고 Flyway 와 스키마 검증을 켠다.
 *
 * <p>`@DynamicPropertySource` 는 검사 클래스마다 컨텍스트 키를 갈라 새 데이터베이스와 새 컨텍스트를 만든다. 이 초기화기는 클래스가
 * 하나뿐이라 키가 같고, `@MysqlIntegrationTest` 를 단 검사가 컨텍스트 하나를 함께 쓴다.
 */
public class SharedMysqlContext implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        MysqlTestDatabase database = MysqlTestDatabase.shared();
        TestPropertyValues.of(
            "spring.datasource.url=" + database.url(),
            "spring.datasource.username=" + database.username(),
            "spring.datasource.password=" + database.password(),
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            // 잠금 검사가 읽기 시점을 따지므로 MySQL 기본값인 REPEATABLE READ 를 풀 설정에도 명시한다
            "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate").applyTo(context);
    }
}
