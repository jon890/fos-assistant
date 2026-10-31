package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ContextConfiguration;

/**
 * 실제 MySQL 에서만 도는 통합 검사의 표지다. {@code @BackendIntegrationTest} 를 단 클래스(또는 그 하위 클래스)에 더한다.
 *
 * <ul>
 * <li>{@code mysql} 태그를 달아 {@code test} 태스크에서 빼고 {@code mysqlMigrationTest} 로 돌린다
 * <li>{@link SharedMysqlContext} 가 {@link MysqlTestDatabase#shared()} 에 붙는다. 이 주석을 단 검사는 컨텍스트와 데이터베이스를 함께 쓴다
 * <li>{@link MysqlCasesOnly} 가 H2 에서 상속한 검사 가운데 {@link MysqlCase} 가 없는 것을 건너뛴다
 * </ul>
 *
 * <p>검사 클래스에 {@code @DynamicPropertySource} 를 두지 않는다. 클래스마다 컨텍스트가 갈라진다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("mysql")
@ContextConfiguration(initializers = SharedMysqlContext.class)
@ExtendWith(MysqlCasesOnly.class)
public @interface MysqlIntegrationTest {
}
