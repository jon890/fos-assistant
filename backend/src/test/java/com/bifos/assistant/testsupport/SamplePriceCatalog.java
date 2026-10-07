package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * 가격표를 {@code pricing/models-dev-sample.json} 으로 둔다. {@link BackendIntegrationTest} 와 함께 단다.
 *
 * <p>기본 설정은 가격표가 없어 금액이 늘 빈다. 금액이 적히는 경로를 보는 검사만 단다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import(SamplePriceCatalogProperties.class)
public @interface SamplePriceCatalog {}
