package com.bifos.assistant.architecture;

import static com.bifos.assistant.architecture.AgentMemoryCollectionServicePlacementTest.ROOT;
import static com.bifos.assistant.architecture.AgentMemoryCollectionServicePlacementTest.assertViolation;
import static com.bifos.assistant.architecture.AgentMemoryCollectionServicePlacementTest.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 독립 리뷰에서 확인한 설정·구현 값·JPA 접근 방식의 반례를 정상 기대값으로 검사한다. */
@Tag("architecture")
class AgentMemoryRoleRegressionTest {
    @TempDir
    Path temporary;


    @Test
    @DisplayName("설정 선언과 중첩 설정 값 graph 는 소유 config 에 둘 수 있다")
    void acceptsNestedConfigurationValues() throws IOException {
        String settings = ROOT + ".memory.agent.config.Settings";
        String config = ROOT + ".agent.memory.config.Setup";
        JavaClasses fixture = compile(Map.of(
            settings, "@org.springframework.boot.context.properties.ConfigurationProperties(\"fixture\") "
                + "public record Settings(java.util.List<Limits> limits) { "
                + "public record Limits(Detail detail) {} public record Detail(int maximum) {} "
                + "public record Unused(int maximum) {} }",
            config, "@org.springframework.context.annotation.Configuration public class Setup {}"));
        assertThat(fixture).hasSize(5);
        ArchitectureRules.featureApplicationRole(ROOT).check(fixture);
    }


    @ParameterizedTest
    @MethodSource("wrongConfigurations")
    @DisplayName("일반 설정 선언과 설정 graph 의 값도 정확한 config 밖에서는 거절한다")
    void rejectsWrongConfiguration(String relative, String declaration) throws IOException {
        String name = ROOT + "." + relative;
        JavaClasses fixture = compile(Map.of(name, declaration));
        assertViolation(ArchitectureRules.featureApplicationRole(ROOT), fixture, name, "config");
    }


    static Stream<Arguments> wrongConfigurations() {
        return Stream.of(
            Arguments.of("agent.memory.application.WrongConfiguration",
                "@org.springframework.context.annotation.Configuration public class WrongConfiguration {}"),
            Arguments.of("memory.agent.config.nested.WrongConfiguration",
                "@org.springframework.context.annotation.Configuration public class WrongConfiguration {}"),
            Arguments.of("memory.agent.application.Settings",
                "@org.springframework.boot.context.properties.ConfigurationProperties(\"fixture\") "
                    + "public record Settings(Limits limits) { public record Limits(int maximum) {} }"));
    }


    @Test
    @DisplayName("설정이 참조하는 중첩 값도 model 로 빼면 config 역할 위반이다")
    void rejectsConfigurationGraphOutsideConfig() throws IOException {
        String settings = ROOT + ".memory.agent.config.Settings";
        String value = ROOT + ".memory.agent.application.model.Value";
        JavaClasses fixture = compile(Map.of(
            settings, "@org.springframework.boot.context.properties.ConfigurationProperties(\"fixture\") "
                + "public record Settings(" + value + ".Limits limits) {}",
            value, "public class Value { public record Limits(int maximum) {} }"));
        assertViolation(ArchitectureRules.featureApplicationRole(ROOT), fixture, value + "$Limits", "config");
    }


    @Test
    @DisplayName("외부에 노출하지 않은 private 비영속 구현 값은 model 안에 둘 수 있다")
    void acceptsPrivateModelImplementation() throws IOException {
        String value = ROOT + ".agent.memory.application.model.Value";
        JavaClasses fixture = compile(Map.of(value,
            "public record Value(String text) { private static class Local { int state; } }"));
        assertThat(fixture).hasSize(2);
        ArchitectureRules.featureApplicationRole(ROOT).check(fixture);
    }


    @ParameterizedTest
    @MethodSource("independentRoles")
    @DisplayName("private 예외로 서비스와 영속 타입 및 실제 상태 helper 를 면제하지 않는다")
    void rejectsIndependentRoles(String declaration, String detail) throws IOException {
        String value = ROOT + ".agent.memory.application.model.Value";
        JavaClasses fixture = compile(Map.of(value, "public class Value { " + declaration + " }"));
        assertViolation(ArchitectureRules.featureApplicationRole(ROOT), fixture, value + "$Local", detail);
    }


    static Stream<Arguments> independentRoles() {
        return Stream.of(
            Arguments.of("public static class Local { int state; }", "상태 helper"),
            Arguments.of("@org.springframework.stereotype.Service private static class Local { int state; }", "Service"),
            Arguments.of("@jakarta.persistence.Entity private static class Local { int state; }", "domain.entity"),
            Arguments.of("@jakarta.persistence.Embeddable private static class Local { int state; }", "domain.entity"),
            Arguments.of("@jakarta.persistence.MappedSuperclass private static class Local { int state; }", "domain.entity"),
            Arguments.of("private static class Local { int state; } public Local result() { return null; }", "상태 helper"));
    }


    @Test
    @DisplayName("중첩 설정 안에 선언한 서비스와 JPA 타입도 자체 역할을 검사한다")
    void rejectsServiceAndEntityInsideSettings() throws IOException {
        String settings = ROOT + ".memory.agent.config.Settings";
        JavaClasses fixture = compile(Map.of(settings,
            "@org.springframework.boot.context.properties.ConfigurationProperties(\"fixture\") public class Settings { "
                + "@org.springframework.stereotype.Service private static class BadService {} "
                + "@jakarta.persistence.Entity private static class BadEntity {} }"));
        assertViolation(ArchitectureRules.featureApplicationRole(ROOT), fixture,
            settings + "$BadService", "Service", settings + "$BadEntity", "domain.entity");
    }


    @ParameterizedTest
    @MethodSource("enumAccess")
    @DisplayName("필드와 property getter 및 상속된 저장 enum 은 소유 domain.type 에서만 허용한다")
    void checksStoredEnumOwnership(boolean property, boolean inherited, String enumPackage, boolean accepted) throws IOException {
        String row = ROOT + ".agent.memory.domain.entity.Row";
        String base = ROOT + ".agent.memory.domain.entity.Base";
        String state = ROOT + "." + enumPackage + ".State";
        String storage = property
            ? "@jakarta.persistence.Id public Long getId() { return null; } "
                + "@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING) public " + state + " getState() { return null; }"
            : "@jakarta.persistence.Id Long id; @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING) " + state + " state;";
        Map<String, String> declarations = new HashMap<>();
        declarations.put(state, "public enum State { OK }");
        declarations.put(row, "@jakarta.persistence.Entity public class Row "
            + (inherited ? "extends Base {}" : "{ " + storage + " }"));
        if (inherited) {
            declarations.put(base, "@jakarta.persistence.MappedSuperclass public class Base { " + storage + " }");
        }
        JavaClasses fixture = compile(declarations);
        assertThat(fixture).hasSize(inherited ? 3 : 2);
        if (accepted) {
            ArchitectureRules.featureApplicationRole(ROOT).check(fixture);
        } else {
            assertViolation(ArchitectureRules.featureApplicationRole(ROOT), fixture, row, "저장 enum", "domain.type");
        }
    }


    static Stream<Arguments> enumAccess() {
        return Stream.of(false, true).flatMap(property -> Stream.of(false, true).flatMap(inherited -> Stream.of(
            Arguments.of(property, inherited, "agent.memory.domain.type", true),
            Arguments.of(property, inherited, "agent.memory.application.model", false),
            Arguments.of(property, inherited, "memory.agent.domain.type", false))));
    }


    private JavaClasses compile(Map<String, String> declarations) throws IOException {
        Map<String, String> sources = new HashMap<>();
        declarations.forEach((name, declaration) -> sources.put(name, source(name, declaration)));
        return ArchitectureRulesTest.compileFixture(temporary, sources);
    }
}
