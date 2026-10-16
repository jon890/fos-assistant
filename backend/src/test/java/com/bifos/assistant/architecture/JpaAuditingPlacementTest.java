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

/** 실제 annotation을 컴파일해 상속·업무 필드·선택 수 회귀를 검사한다. */
@Tag("architecture")
class JpaAuditingPlacementTest {
    private static final String COLLECTION = "com.bifos.assistant.memory.domain.entity.MemoryCollection";
    private static final String CREATED_SOURCE = """
            package com.bifos.assistant.shared.domain;
            import java.time.Instant;
            import jakarta.persistence.*;
            import org.springframework.data.annotation.CreatedDate;
            import org.springframework.data.jpa.domain.support.AuditingEntityListener;
            @MappedSuperclass @EntityListeners(AuditingEntityListener.class)
            public abstract class CreatedAtEntity {
                @CreatedDate @Column(name="created_at", nullable=false, updatable=false) Instant createdAt;
            }
            """;
    private static final String AUDITED_SOURCE = """
            package com.bifos.assistant.shared.domain;
            import java.time.Instant;
            import jakarta.persistence.*;
            import org.springframework.data.annotation.LastModifiedDate;
            @MappedSuperclass
            public abstract class AuditedEntity extends CreatedAtEntity {
                @LastModifiedDate @Column(name="updated_at") Instant updatedAt;
            }
            """;
    private static final String CONFIG_SOURCE = """
            package com.bifos.assistant.shared.config;
            import java.time.Clock;
            import java.time.temporal.ChronoUnit;
            import java.util.Optional;
            import org.springframework.context.annotation.*;
            import org.springframework.data.auditing.DateTimeProvider;
            import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
            @Configuration @EnableJpaAuditing(dateTimeProviderRef="auditingDateTimeProvider")
            public class JpaAuditingConfig {
                @Bean("auditingDateTimeProvider") DateTimeProvider provider(Clock clock) {
                    return () -> Optional.of(clock.instant().truncatedTo(ChronoUnit.MICROS));
                }
            }
            """;

    @TempDir
    Path temporary;


    @Test
    @DisplayName("생성 전용과 생성 변경 및 업무 시각 제외의 전수 판정을 허용한다")
    void acceptsCompleteInventory() throws IOException {
        ArchitectureRules.AUDITING_LISTENERS_AND_CLOCK.check(ArchitectureRulesTest.compileFixture(temporary, sources()));
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "created", "modified", "listener", "mapped", "baseId", "column", "type", "inheritance",
        "missingEntity", "duplicateEntity", "unexpectedEntity", "unnecessaryModified", "businessInheritance",
        "missingConfig", "duplicateConfig", "enable", "configuration", "reference", "bean", "clock", "return", "getter", "actor", "auditable"
    })
    @DisplayName("누락과 중복 및 감사 범위 밖의 선언은 실제 구조 규칙으로 실패한다")
    void rejectsInvalidDeclarations(String mutation) throws IOException {
        Map<String, String> sources = sources();
        String created = sources.get(JpaAuditingRules.CREATED);
        String audited = sources.get(JpaAuditingRules.AUDITED);
        String config = sources.get(JpaAuditingRules.CONFIG);
        switch (mutation) {
            case "created" -> created = created.replace("@CreatedDate ", "");
            case "modified" -> audited = audited.replace("@LastModifiedDate ", "");
            case "listener" -> created = created.replace("@EntityListeners(AuditingEntityListener.class)", "");
            case "mapped" -> created = created.replace("@MappedSuperclass ", "");
            case "baseId" -> created = created.replace("Instant createdAt;", "Instant createdAt; @Id Long id;");
            case "column" -> created = created.replace("updatable=false", "updatable=true");
            case "type" -> created = created.replace("Instant createdAt", "String createdAt");
            case "inheritance" -> audited = audited.replace(" extends CreatedAtEntity", "");
            case "missingEntity" -> sources.remove(COLLECTION);
            case "duplicateEntity" -> sources.put("fixture.Duplicate", entity("fixture.Duplicate", "memory_collection", "C"));
            case "unexpectedEntity" -> sources.put("fixture.Unclassified", entity("fixture.Unclassified", "unclassified", "C"));
            case "unnecessaryModified" -> sources.put(COLLECTION, sources.get(COLLECTION).replace("extends CreatedAtEntity", "extends AuditedEntity"));
            case "businessInheritance" -> {
                String name = "com.bifos.assistant.task.domain.TaskRun";
                sources.put(name, entity(name, "task_run", "C"));
            }
            case "missingConfig" -> config = null;
            case "duplicateConfig" -> sources.put("fixture.OtherConfig", CONFIG_SOURCE
                .replace("com.bifos.assistant.shared.config", "fixture").replace("JpaAuditingConfig", "OtherConfig"));
            case "enable" -> config = config.replace("@EnableJpaAuditing(dateTimeProviderRef=\"auditingDateTimeProvider\")", "");
            case "configuration" -> config = config.replace("@Configuration ", "");
            case "reference" -> config = config.replace("dateTimeProviderRef=\"auditingDateTimeProvider\"", "dateTimeProviderRef=\"wrong\"");
            case "bean" -> config = config.replace("@Bean(\"auditingDateTimeProvider\")", "");
            case "clock" -> config = config.replace("Clock clock", "Clock clock, String extra");
            case "return" -> config = config.replace("DateTimeProvider provider", "Object provider").replace("return () ->", "return (DateTimeProvider) () ->");
            case "getter" -> created = created.replace("Instant createdAt;", "Instant createdAt; @CreatedDate Instant getCreatedAt() { return createdAt; }");
            case "actor" -> created = created.replace("@CreatedDate ", "@org.springframework.data.annotation.CreatedBy @CreatedDate ");
            case "auditable" -> sources.put("fixture.Other", "package fixture; public abstract class Other extends org.springframework.data.jpa.domain.AbstractAuditable<String, Long> {}");
            default -> throw new IllegalArgumentException(mutation);
        }
        sources.put(JpaAuditingRules.CREATED, created);
        sources.put(JpaAuditingRules.AUDITED, audited);
        if (config == null) {
            sources.remove(JpaAuditingRules.CONFIG);
        } else {
            sources.put(JpaAuditingRules.CONFIG, config);
        }
        assertThat(ArchitectureRules.AUDITING_LISTENERS_AND_CLOCK.evaluate(ArchitectureRulesTest.compileFixture(temporary, sources)).hasViolation())
            .as(mutation).isTrue();
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "Conversation.updatedAt", "ConnectorConnection.updatedAt", "ConnectorBinding.updatedAt", "Memory.updatedAt",
        "UserBrowser.updatedAt", "ResultDelivery.updatedAt", "ContentDraftSnapshot.connectionUpdatedAt",
        "ConnectorActionExecution.bindingUpdatedAt", "TaskRun.startedAt", "TaskRun.finishedAt", "ContentDraftJob.startClaimedAt",
        "ContentDraftRevision.createdAt", "MediaObservation.createdAt", "SubagentUsageJob.createdAt", "AttentionEvent.createdAt"
    })
    @DisplayName("활동과 snapshot 및 사건과 revision 및 큐 업무 시각에는 auditing을 붙이지 못한다")
    void rejectsBusinessTimestampAnnotations(String property) throws IOException {
        Map<String, String> sources = sources();
        String[] parts = property.split("\\.");
        String name = JpaAuditingRules.ENTITIES.keySet().stream().filter(candidate -> candidate.endsWith("." + parts[0])).findFirst().orElseThrow();
        String source = sources.get(name);
        if (parts[1].equals("createdAt")) {
            source = source.replace("@Column(name=\"created_at\")", "@org.springframework.data.annotation.CreatedDate @Column(name=\"created_at\")");
        } else {
            source = source.replace("{", "{ @org.springframework.data.annotation.LastModifiedDate Instant " + parts[1] + ";");
        }
        sources.put(name, source);
        assertThat(ArchitectureRules.AUDITING_LISTENERS_AND_CLOCK.evaluate(ArchitectureRulesTest.compileFixture(temporary, sources)).hasViolation())
            .as(property).isTrue();
    }


    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(JpaAuditingRules.CREATED, CREATED_SOURCE);
        sources.put(JpaAuditingRules.AUDITED, AUDITED_SOURCE);
        sources.put(JpaAuditingRules.CONFIG, CONFIG_SOURCE);
        JpaAuditingRules.ENTITIES.forEach((name, contract) -> sources.put(name, entity(name, contract.table(), contract.policy())));
        return sources;
    }


    private static String entity(String name, String table, String policy) {
        int dot = name.lastIndexOf('.');
        String parent = policy.equals("C+U") ? " extends AuditedEntity" : policy.equals("C") ? " extends CreatedAtEntity" : "";
        String field = policy.equals("제외") ? "@Column(name=\"created_at\") Instant createdAt;" : "";
        return "package " + name.substring(0, dot) + "; import jakarta.persistence.*; import java.time.Instant; "
            + "import com.bifos.assistant.shared.domain.*; @Entity @Table(name=\"" + table + "\") public class "
            + name.substring(dot + 1) + parent + " { " + field + " }";
    }


    @ParameterizedTest
    @ValueSource(strings = {"occurredAt", "changedAt", "claimedAt", "startedAt", "finishedAt"})
    @DisplayName("별도 사건과 revision 및 큐 시각도 생성 시각 annotation을 받지 못한다")
    void rejectsOccurrenceAndRevisionAuditing(String field) throws IOException {
        Map<String, String> sources = sources();
        sources.put("fixture.Event", "package fixture; public class Event { @org.springframework.data.annotation.CreatedDate java.time.Instant " + field + "; }");
        assertThat(ArchitectureRules.AUDITING_LISTENERS_AND_CLOCK.evaluate(ArchitectureRulesTest.compileFixture(temporary, sources)).hasViolation()).isTrue();
    }
}
