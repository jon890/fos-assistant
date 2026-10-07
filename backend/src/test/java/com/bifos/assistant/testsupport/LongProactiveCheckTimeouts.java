package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * Hermes 실행 상한을 30초, 살펴보기 시간 상한을 20초로 늘린다. {@link BackendIntegrationTest} 와 함께 단다.
 *
 * <p>살펴보기와 예약 실행이 끝까지 도는 경로를 보는 검사가 쓴다. 느린 실행 환경에서도 상한에 걸려 끊기지 않게 한다. 같은 값 묶음을 쓰는
 * 검사는 컨텍스트를 함께 쓴다. 여러 변형을 함께 달 때는 {@code docs/backend/testing.md} 「변형」 표의 순서대로 단다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@TestPropertySource(properties = {"hermes.run-timeout=30s", "assistant.proactive-check.max-duration=20s"})
public @interface LongProactiveCheckTimeouts {}
