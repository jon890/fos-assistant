package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * H2 에서 도는 검사 가운데 실제 MySQL 에서도 다시 돌려야 하는 메서드(또는 클래스)에 붙인다.
 *
 * <p>{@link MysqlIntegrationTest} 를 단 하위 클래스는 H2 검사를 상속하지만, 이 주석이 없는 상위 클래스 메서드는 돌지 않는다
 * ({@link MysqlCasesOnly}). 하위 클래스가 직접 선언한 메서드는 MySQL 전용이라 늘 돈다.
 *
 * <p>값은 H2 와 MySQL 이 실제로 다르게 동작하는 이유다. 이유가 없는 검사는 MySQL 에서 다시 돌리지 않는다.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MysqlCase {

    Reason value();


    /** H2 와 MySQL 이 다르게 동작하는 이유다. */
    enum Reason {
        /** 행 잠금, 유일 제약 경합, 격리 수준처럼 동시 트랜잭션이 서로 기다리거나 부딪치는 동작이다. */
        ROW_LOCK,
        /** 한글과 대소문자 정렬 규칙이 비교, 검색 일치, 정렬 결과를 가르는 동작이다. */
        COLLATION,
        /** Flyway 마이그레이션이 실제 DDL 로 만든 스키마(FK, 제약, 색인)에 기대는 동작이다. */
        MIGRATION_DDL,
        /** 저장소 인터페이스 밖의 쿼리가 MySQL 문법을 지나는지 본다(rule-51 이 이 검사에 맡긴 몫). */
        QUERY_OUTSIDE_REPOSITORY
    }
}
