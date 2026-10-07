package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * 민감 memory 암호화 key 를 비워 암호화가 꺼진 동작을 만든다. {@link BackendIntegrationTest} 와 함께 단다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@TestPropertySource(properties = {"assistant.memory.encryption.active-key-id=", "assistant.memory.encryption.keys="})
public @interface MemoryEncryptionDisabled {}
