package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 실제 규칙 하나로 운영 소유와 컴파일한 정상·위반 사례를 확인한다. */
@Tag("architecture")
class AgentPropertiesPlacementTest {

    private static final String OWNER = "com.bifos.assistant.agent.config";
    private static final String AGENTS = OWNER + ".AgentProperties";
    private static final String PEOPLE = OWNER + ".PeopleProperties";

    @TempDir
    Path temporary;


    @Test
    @DisplayName("운영 설정 두 타입과 config 역할을 기준 파일 없이 검사한다")
    void checksProductionOwnership() {
        ArchitectureRules.FEATURE_CONFIG_PLACEMENT.check(ArchitectureRulesTest.MAIN);
    }


    @Test
    @DisplayName("설정과 Configuration은 config에 있고 하위 서비스가 타입으로 주입받는다")
    void acceptsConfigAndTypedConsumer() throws IOException {
        Map<String, String> sources = validSources();
        sources.put(OWNER + ".AgentConfiguration", """
                package com.bifos.assistant.agent.config;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                public class AgentConfiguration {}
                """);
        sources.put("com.bifos.assistant.agent.fixture.application.UseCase", """
                package com.bifos.assistant.agent.fixture.application;
                import com.bifos.assistant.agent.config.AgentProperties;
                import org.springframework.stereotype.Service;
                @Service
                public class UseCase { public UseCase(AgentProperties properties) {} }
                """);
        JavaClasses fixture = compile(sources);
        assertThat(fixture).hasSize(4);
        ArchitectureRules.FEATURE_CONFIG_PLACEMENT.check(fixture);
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "com.bifos.assistant.agent.application", "com.bifos.assistant.agent.application.model",
        "com.bifos.assistant.people.config"
    })
    @DisplayName("설정을 옛 package와 공개 값 또는 다른 기능으로 옮기면 실패한다")
    void rejectsWrongOwner(String owner) throws IOException {
        Map<String, String> sources = validSources();
        sources.remove(AGENTS);
        String name = owner + ".AgentProperties";
        sources.put(name, properties(owner, "AgentProperties", "assistant.agents"));
        assertViolation(compile(sources), name, "설정 소유 package");
    }


    @ParameterizedTest
    @ValueSource(strings = {"AgentProperties", "PeopleProperties"})
    @DisplayName("설정 하나가 빠져도 빈 선택으로 통과하지 않는다")
    void rejectsMissingType(String name) throws IOException {
        Map<String, String> sources = validSources();
        sources.remove(OWNER + "." + name);
        assertViolation(compile(sources), OWNER + "." + name, "정확히 1개");
    }


    @Test
    @DisplayName("두 설정이 모두 없어도 실패한다")
    void rejectsMissingBothTypes() throws IOException {
        JavaClasses fixture = compile(Map.of("unrelated.Other", "package unrelated; public class Other {}"));
        assertViolation(fixture, AGENTS, "정확히 1개");
        assertViolation(fixture, PEOPLE, "정확히 1개");
    }


    @Test
    @DisplayName("다른 package에 같은 top-level 설정을 복제하면 실패한다")
    void rejectsDuplicateType() throws IOException {
        Map<String, String> sources = validSources();
        String duplicate = "com.bifos.assistant.agent.application.AgentProperties";
        sources.put(duplicate, properties("com.bifos.assistant.agent.application", "AgentProperties", "assistant.agents"));
        assertViolation(compile(sources), duplicate, "정확히 1개");
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "jakarta.persistence.Entity", "jakarta.persistence.Embeddable",
        "org.springframework.stereotype.Service", "org.springframework.stereotype.Repository",
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController"
    })
    @DisplayName("config에 영속 타입이나 서비스와 저장소와 컨트롤러를 두면 실패한다")
    void rejectsWrongAnnotatedRole(String annotation) throws IOException {
        Map<String, String> sources = validSources();
        String name = OWNER + ".WrongRole";
        sources.put(name, "package " + OWNER + "; @" + annotation
            + " @org.springframework.context.annotation.Configuration public class WrongRole {}");
        assertViolation(compile(sources), name, "배치하지 않음");
    }


    @ParameterizedTest
    @ValueSource(strings = {"public record WrongRole(String value) {}", "public enum WrongRole { VALUE }"})
    @DisplayName("공개 서비스 값과 DTO와 enum은 config에 두지 않는다")
    void rejectsPlainValueRole(String declaration) throws IOException {
        Map<String, String> sources = validSources();
        sources.put(OWNER + ".WrongRole", "package " + OWNER + "; " + declaration);
        assertViolation(compile(sources), OWNER + ".WrongRole", "ConfigurationProperties 또는 Configuration");
    }


    @Test
    @DisplayName("prefix와 Validated와 public record 계약이 바뀌면 실패한다")
    void rejectsChangedDeclaration() throws IOException {
        Map<String, String> sources = validSources();
        sources.put(AGENTS, "package " + OWNER
            + "; @org.springframework.boot.context.properties.ConfigurationProperties(prefix=\"changed\")"
            + " class AgentProperties {}");
        JavaClasses fixture = compile(sources);
        assertViolation(fixture, AGENTS, "설정 prefix");
        assertViolation(fixture, AGENTS, "Validated");
        assertViolation(fixture, AGENTS, "public record");
        assertThat(ArchitectureRules.CONFIGURATION_PROPERTIES_ARE_VALIDATED.evaluate(fixture).hasViolation()).isTrue();
    }


    @ParameterizedTest
    @ValueSource(strings = {"prefix=\"assistant.people\"", "value=\"assistant.people\"", "\"assistant.people\""})
    @DisplayName("다른 이름과 package가 prefix 또는 value 별칭을 선언하면 실패한다")
    void rejectsPrefixAlias(String declaration) throws IOException {
        Map<String, String> sources = validSources();
        sources.put(OWNER + ".Alias", properties(OWNER, "Alias", "assistant.people")
            .replace("prefix=\"assistant.people\"", declaration));
        String other = "com.bifos.assistant.chat.application";
        sources.put(other + ".Alias", properties(other, "Alias", "assistant.people")
            .replace("prefix=\"assistant.people\"", declaration));
        JavaClasses fixture = compile(sources);
        assertViolation(fixture, OWNER + ".Alias", "지정한 두 설정");
        assertViolation(fixture, other + ".Alias", "지정한 두 설정");
    }


    @ParameterizedTest
    @ValueSource(strings = {"value=", ""})
    @DisplayName("소유 설정 두 타입은 value 별칭으로 선언해도 통과한다")
    void acceptsOwnedValueAlias(String attribute) throws IOException {
        Map<String, String> sources = validSources();
        sources.replaceAll((name, source) -> source.replace("prefix=", attribute));
        ArchitectureRules.FEATURE_CONFIG_PLACEMENT.check(compile(sources));
    }


    @ParameterizedTest
    @ValueSource(strings = {"AgentProperties", "Alias"})
    @DisplayName("prefix와 value가 서로 다르면 소유 타입과 별칭 모두 실패한다")
    void rejectsConflictingAliases(String name) throws IOException {
        Map<String, String> sources = validSources();
        sources.put(OWNER + "." + name, properties(OWNER, name, "assistant.agents")
            .replace("prefix=\"assistant.agents\"", "prefix=\"assistant.agents\", value=\"changed\""));
        assertViolation(compile(sources), OWNER + "." + name, "충돌하지 않음");
    }


    @Test
    @DisplayName("소유 설정 record의 중첩 값 record는 통과한다")
    void acceptsOwnedNestedRecords() throws IOException {
        Map<String, String> sources = validSources();
        sources.replaceAll((name, source) -> source.replace("{}",
            "{ public record Nested(String value) { public record Deep(String value) {} } }"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(6);
        ArchitectureRules.FEATURE_CONFIG_PLACEMENT.check(fixture);
    }


    @ParameterizedTest
    @ValueSource(strings = {"jakarta.persistence.Entity", "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository", "org.springframework.stereotype.Controller"})
    @DisplayName("소유 설정 안의 중첩 record라도 서비스 역할은 실패한다")
    void rejectsNestedAnnotatedRole(String annotation) throws IOException {
        Map<String, String> sources = validSources();
        sources.put(AGENTS, sources.get(AGENTS).replace("{}",
            "{ @" + annotation + " public record Nested(String value) {} }"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(3);
        assertViolation(fixture, AGENTS + "$Nested", "배치하지 않음");
    }


    @Test
    @DisplayName("일반 Configuration의 중첩 DTO는 설정 소유 값으로 허용하지 않는다")
    void rejectsNestedConfigurationDto() throws IOException {
        Map<String, String> sources = validSources();
        sources.put(OWNER + ".Other", "package " + OWNER
            + "; @org.springframework.context.annotation.Configuration public class Other {"
            + " public record Nested(String value) {} }");
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(4);
        assertViolation(fixture, OWNER + ".Other$Nested", "ConfigurationProperties 또는 Configuration");
    }


    private JavaClasses compile(Map<String, String> sources) throws IOException {
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(sources.size());
        return fixture;
    }


    private static void assertViolation(JavaClasses fixture, String name, String reason) {
        var result = ArchitectureRules.FEATURE_CONFIG_PLACEMENT.evaluate(fixture);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains(name, reason, "에이전트 설정은 agent.config");
    }


    private static Map<String, String> validSources() {
        return new HashMap<>(Map.of(
            AGENTS, properties(OWNER, "AgentProperties", "assistant.agents"),
            PEOPLE, properties(OWNER, "PeopleProperties", "assistant.people")));
    }


    private static String properties(String owner, String name, String prefix) {
        return "package " + owner + "; @org.springframework.validation.annotation.Validated "
            + "@org.springframework.boot.context.properties.ConfigurationProperties(prefix=\"" + prefix + "\") "
            + "public record " + name + "(String value) {}";
    }
}
