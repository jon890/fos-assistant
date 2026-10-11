package com.bifos.assistant.testsupport;

import com.bifos.assistant.AssistantApplication;
import com.bifos.persistencefixture.PersistenceExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 정상 저장 타입 추가를 재현할 때만 별도 Spring 문맥의 탐색 범위를 넓힌다. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(PersistenceExtensionIntegrationTest.ExtensionConfiguration.class)
public @interface PersistenceExtensionIntegrationTest {
    @TestConfiguration
    @EntityScan(basePackageClasses = {AssistantApplication.class, PersistenceExtension.class})
    @EnableJpaRepositories(basePackageClasses = {AssistantApplication.class, PersistenceExtension.class}, considerNestedRepositories = true)
    class ExtensionConfiguration {
    }
}
