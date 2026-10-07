package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * 사용자 동시 실행 한도를 2 로 둔다. {@link BackendIntegrationTest} 와 함께 단다.
 *
 * <p>한도에 닿는 경로를 보는 검사가 쓴다. 같은 값 묶음을 쓰는 검사는 컨텍스트를 함께 쓴다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@TestPropertySource(properties = "assistant.user-execution.max-running=2")
public @interface SmallExecutionLimit {}
