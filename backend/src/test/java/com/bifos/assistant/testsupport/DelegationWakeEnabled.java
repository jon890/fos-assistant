package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 위임 결과로 다음 turn 을 여는 깨우기를 켠다. {@link BackendIntegrationTest} 와 함께 단다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@OverrideProperties("assistant.delegation-wake.enabled=true")
public @interface DelegationWakeEnabled {}
