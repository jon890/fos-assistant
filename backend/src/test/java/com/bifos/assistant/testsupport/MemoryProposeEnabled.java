package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * 대화 뒤 Memory 제안을 만드는 기능을 켠다. {@link BackendIntegrationTest} 와 함께 단다.
 *
 * <p>기본 설정은 제안이 꺼져 있다. 제안이 만들어지는 경로를 보는 검사만 단다. 같은 값 묶음을 쓰는 검사는 컨텍스트를 함께 쓴다. 여러 변형을
 * 함께 달 때는 {@code docs/backend/testing.md} 「변형」 표의 순서대로 단다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@TestPropertySource(properties = "assistant.memory.propose.enabled=true")
public @interface MemoryProposeEnabled {}
