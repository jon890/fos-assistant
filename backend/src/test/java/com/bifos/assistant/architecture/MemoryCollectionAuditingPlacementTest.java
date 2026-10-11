package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 실제 annotation 을 컴파일하고 운영 auditing 규칙으로 선언 누락과 범위 확대를 거절한다. */
@Tag("architecture")
class MemoryCollectionAuditingPlacementTest {
    private static final String ENTITY = "com.bifos.assistant.memory.domain.MemoryCollection";
    private static final String CONFIG = "com.bifos.assistant.shared.config.JpaAuditingConfig";
    private static final String ENTITY_SOURCE = """
            package com.bifos.assistant.memory.domain;
            import jakarta.persistence.*;
            import java.time.Instant;
            import org.springframework.data.annotation.CreatedDate;
            import org.springframework.data.jpa.domain.support.AuditingEntityListener;
            @Entity @Table(name="memory_collection") @EntityListeners(AuditingEntityListener.class)
            public class MemoryCollection {
                @Id Long id;
                @CreatedDate Instant createdAt;
            }
            """;
    private static final String CONFIG_SOURCE = """
            package com.bifos.assistant.shared.config;
            import java.time.Clock;
            import java.time.Instant;
            import java.time.temporal.ChronoUnit;
            import java.util.Optional;
            import org.springframework.context.annotation.*;
            import org.springframework.data.auditing.DateTimeProvider;
            import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
            @Configuration @EnableJpaAuditing(dateTimeProviderRef="auditingDateTimeProvider")
            public class JpaAuditingConfig {
                @Bean("auditingDateTimeProvider")
                DateTimeProvider provider(Clock clock) {
                    return () -> Optional.of(clock.instant().truncatedTo(ChronoUnit.MICROS));
                }
            }
            """;

    @TempDir
    Path temporary;


    @Test
    @DisplayName("현재 domain 위치의 생성 시각과 정상 provider 선언을 허용한다")
    void acceptsActualAnnotations() throws IOException {
        ArchitectureRules.AUDITING_LISTENERS_AND_CLOCK.check(
            ArchitectureRulesTest.compileFixture(temporary, Map.of(ENTITY, ENTITY_SOURCE, CONFIG, CONFIG_SOURCE)));
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "created", "type", "configuration", "enable", "beanMissing", "listener", "business", "other", "getter", "reference", "bean", "return", "clock",
        "missingEntity", "duplicateEntity", "missingConfig", "duplicateConfig", "duplicateProvider"
    })
    @DisplayName("annotation 과 listener 와 provider 선언 누락 및 중복은 운영 규칙으로 실패한다")
    void rejectsInvalidDeclarations(String change) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        String entity = ENTITY_SOURCE;
        String config = CONFIG_SOURCE;
        switch (change) {
            case "created" -> entity = entity.replace("@CreatedDate Instant", "Instant");
            case "type" -> entity = entity.replace("Instant createdAt", "String createdAt");
            case "configuration" -> config = config.replace("@Configuration ", "");
            case "enable" -> config = config.replace("@EnableJpaAuditing(dateTimeProviderRef=\"auditingDateTimeProvider\")", "");
            case "beanMissing" -> config = config.replace("@Bean(\"auditingDateTimeProvider\")", "");
            case "listener" -> entity = entity.replace("@EntityListeners(AuditingEntityListener.class)", "");
            case "business" -> entity = entity.replace("@Id Long id;", "@Id Long id; @CreatedDate Instant expiresAt;");
            case "other" -> sources.put("fixture.Other", """
                    package fixture;
                    import org.springframework.data.annotation.LastModifiedDate;
                    import java.time.Instant;
                    public class Other { @LastModifiedDate Instant changedAt; }
                    """);
            case "getter" -> entity = entity.replace("@Id Long id;", "@Id Long id; @CreatedDate Instant getCreatedAt() { return createdAt; }");
            case "reference" -> config = config.replace("dateTimeProviderRef=\"auditingDateTimeProvider\"", "dateTimeProviderRef=\"wrong\"");
            case "bean" -> config = config.replace("@Bean(\"auditingDateTimeProvider\")", "@Bean(\"wrong\")");
            case "return" -> config = config.replace("DateTimeProvider provider", "Instant provider")
                .replace("return () -> Optional.of(clock.instant().truncatedTo(ChronoUnit.MICROS));", "return clock.instant();");
            case "clock" -> config = config.replace("provider(Clock clock)", "provider()")
                .replace("clock.instant()", "Instant.EPOCH");
            case "missingEntity" -> entity = null;
            case "duplicateEntity" -> sources.put("fixture.MemoryCollection", ENTITY_SOURCE.replace("com.bifos.assistant.memory.domain", "fixture"));
            case "missingConfig" -> config = null;
            case "duplicateConfig" -> sources.put("fixture.JpaAuditingConfig", CONFIG_SOURCE.replace("com.bifos.assistant.shared.config", "fixture"));
            case "duplicateProvider" -> config = config.replace("public class JpaAuditingConfig {", "public class JpaAuditingConfig { @Bean(\"auditingDateTimeProvider\") DateTimeProvider duplicate(Clock clock) { return () -> Optional.of(clock.instant()); }");
            default -> throw new IllegalArgumentException(change);
        }
        if (entity != null) {
            sources.put(ENTITY, entity);
        }
        if (config != null) {
            sources.put(CONFIG, config);
        }
        var result = ArchitectureRules.AUDITING_LISTENERS_AND_CLOCK.evaluate(
            ArchitectureRulesTest.compileFixture(temporary, sources));
        assertThat(result.hasViolation()).as(change).isTrue();
        assertThat(result.getFailureReport().toString()).contains("auditing", "MemoryCollection");
    }
}
