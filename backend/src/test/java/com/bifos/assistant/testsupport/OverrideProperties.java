package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 검사가 쓰는 동안 {@link com.bifos.assistant.shared.config.LiveProperties} 의 값을 바꾼다. {@link BackendIntegrationTest} 와
 * 함께 단다.
 *
 * <p>값은 {@code application.yml} 키 모양의 {@code "<키>=<값>"} 이다. {@link IntegrationTestIsolation} 이 검사마다 시작 전에
 * 적용하고 끝난 뒤 기동 값으로 되돌린다. 컨텍스트는 새로 뜨지 않는다. 검사 클래스와 그 상위 클래스, 메타 주석의 값을 모두 모으고, 같은 키는
 * 검사 클래스에 가까운 값이 이긴다.
 *
 * <p>{@code LiveProperties} 로 읽지 않는 키를 적으면 적용할 때 실패한다. 쓰는 법은 {@code backend/docs/code-architecture.md} 「설정 바꾸기」
 * 가 갖는다.
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Repeatable(OverrideProperties.List.class)
public @interface OverrideProperties {

    /** {@code "<키>=<값>"} 모양의 설정이다. */
    String[] value();

    /** 한 자리에 {@link OverrideProperties} 를 여럿 달 때 쓰는 컨테이너다. */
    @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @Inherited
    @interface List {

        OverrideProperties[] value();
    }
}
