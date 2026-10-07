package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * 위임 결과로 다음 turn 을 여는 깨우기를 켠다. {@link BackendIntegrationTest} 와 함께 단다.
 *
 * <p>같은 값 묶음을 쓰는 검사는 컨텍스트를 함께 쓴다. 여러 변형을 함께 달 때는 {@code docs/backend/testing.md} 「변형」 표의 순서대로 단다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@TestPropertySource(properties = "assistant.delegation-wake.enabled=true")
public @interface DelegationWakeEnabled {}
