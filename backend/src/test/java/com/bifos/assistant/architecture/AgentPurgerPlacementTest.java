package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 지연 정리의 운영 소유와 정상·위반 소스를 같은 raw 규칙으로 검사한다. */
@Tag("architecture")
class AgentPurgerPlacementTest {

    private static final String ROOT = "com.bifos.assistant.fixture";
    private static final String APPLICATION = ROOT + ".agent.purge.application.";
    private static final Set<String> NAMES = Set.of(
        "AgentPurger", "AgentPurgeWriter", "AgentPurgeParticipant", "AgentPurgeOutcome");

    @TempDir
    Path temporary;


    @Test
    @DisplayName("운영 루트 전체에서 정리 소유 타입 네 개를 각각 하나 선택하고 raw 규칙을 통과한다")
    void checksProductionOwnership() {
        JavaClasses production = ArchitectureRulesTest.MAIN;
        for (String name : NAMES) {
            assertThat(production.stream()
                .filter(type -> type.getEnclosingClass().isEmpty() && type.getSimpleName().equals(name))
                .toList()).as("운영 소유 타입 %s", name).hasSize(1);
        }
        ArchitectureRules.AGENT_PURGE_CONTRACT_PLACEMENT.check(production);
    }


    @Test
    @DisplayName("정상 소유와 application 에서 infra 호출 및 다른 기능의 참여 구현을 허용한다")
    void acceptsOwnedFixture() throws IOException {
        Map<String, String> sources = sources();
        String store = ROOT + ".agent.infra.Store";
        sources.put(store, source(store, "public class Store {}"));
        sources.compute(APPLICATION + "AgentPurgeWriter", (name, body) -> body.replace("{}", "{ " + store + " store; }"));
        String participant = ROOT + ".connector.application.ConnectorAgentPurge";
        sources.put(participant, source(participant,
            "class ConnectorAgentPurge implements " + APPLICATION + "AgentPurgeParticipant {}"));
        String controller = ROOT + ".agent.purge.presentation.Controller";
        String policy = ROOT + ".agent.purge.domain.Policy";
        sources.put(controller, source(controller, "public class Controller { " + APPLICATION + "AgentPurger purger; }"));
        sources.put(policy, source(policy, "public class Policy {}"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(8);
        rule().check(fixture);
        ArchitectureRules.LAYER_DIRECTION.check(fixture);
        ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_WEB.check(fixture);
    }


    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("wrongPlacements")
    @DisplayName("소유 타입의 옛 위치와 다른 기능 및 잘못된 층으로의 이동을 거절한다")
    void rejectsWrongPlacement(String name, String relativePackage) throws IOException {
        Map<String, String> sources = sources();
        String old = fqn(name);
        String target = ROOT + "." + relativePackage + "." + name;
        String body = sources.remove(old);
        sources.put(target, body.replace(old.substring(0, old.lastIndexOf('.')), target.substring(0, target.lastIndexOf('.'))));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(4);
        assertViolation(fixture, target, "에 있어야 한다");
    }


    static Stream<Arguments> wrongPlacements() {
        return names().flatMap(name -> Stream.of(
            Arguments.of(name, name.equals("AgentPurgeOutcome") ? "agent.application.model" : "agent.application"),
            Arguments.of(name, "agent.purge.domain"),
            Arguments.of(name, "connector.application")));
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    @DisplayName("각 소유 타입의 누락은 정확한 기대 수 1로 거절한다")
    void rejectsMissingType(String name) throws IOException {
        Map<String, String> sources = sources();
        sources.remove(fqn(name));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(3);
        assertViolation(fixture, name, "대상 수가 0", "기대값은 1");
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    @DisplayName("다른 기능에 중복 선언한 소유 타입도 전체 루트에서 찾아 거절한다")
    void rejectsDuplicateType(String name) throws IOException {
        Map<String, String> sources = sources();
        String duplicate = ROOT + ".connector.application." + name;
        sources.put(duplicate, source(duplicate, "public class " + name + " {}"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(5);
        assertViolation(fixture, duplicate, "대상 수가 2", "기대값은 1");
    }


    @Test
    @DisplayName("소유 선택이 비거나 전체 루트가 비면 raw 검사가 실패한다")
    void rejectsEmptySelection() throws IOException {
        String unrelated = ROOT + ".Unrelated";
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary.resolve("unrelated"),
            Map.of(unrelated, source(unrelated, "public class Unrelated {}")));
        assertThat(fixture).hasSize(1);
        assertViolation(fixture, "AgentPurger", "대상 수가 0");
        JavaClasses outside = ArchitectureRulesTest.compileFixture(temporary.resolve("outside"),
            Map.of("outside.Unrelated", "package outside; public class Unrelated {}"));
        assertThat(outside).hasSize(1);
        assertThatThrownBy(() -> rule().check(outside)).isInstanceOf(AssertionError.class);
    }


    @Test
    @DisplayName("동일 이름의 중첩 값은 top-level 소유 선택에 넣지 않는다")
    void ignoresNestedNames() throws IOException {
        Map<String, String> sources = sources();
        String outer = ROOT + ".agent.purge.application.Internal";
        sources.put(outer, source(outer, "class Internal { record AgentPurgeOutcome() {} }"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(6);
        rule().check(fixture);
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRoles")
    @DisplayName("정리 하위 기능의 잘못된 서비스·공개 값·설정 역할을 거절한다")
    void rejectsInvalidRole(String relative, String declaration, String detail) throws IOException {
        Map<String, String> sources = sources();
        String name = ROOT + ".agent.purge." + relative;
        sources.put(name, source(name, declaration));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(5);
        assertViolation(fixture, name, detail);
    }


    static Stream<Arguments> invalidRoles() {
        return Stream.of(
            Arguments.of("application.model.BadService", "@org.springframework.stereotype.Service public class BadService {}", "Service·Component"),
            Arguments.of("domain.BadComponent", "@org.springframework.stereotype.Component public class BadComponent {}", "Service·Component"),
            Arguments.of("application.BadValue", "public record BadValue(String value) {}", "application.model"),
            Arguments.of("application.BadResult", "public enum BadResult { WAITING }", "application.model"),
            Arguments.of("application.BadProperties", "@org.springframework.boot.context.properties.ConfigurationProperties(\"fixture\") public record BadProperties(String value) {}", "config"));
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongDirections")
    @DisplayName("purge 하위 층의 역방향과 웹 의존 및 참여 구현 import 를 거절한다")
    void rejectsWrongDirection(String relative, String targetRelative, String detail) throws IOException {
        Map<String, String> sources = sources();
        String name = ROOT + ".agent.purge." + relative;
        String target = targetRelative.startsWith("org.") ? targetRelative : ROOT + "." + targetRelative;
        if (!targetRelative.startsWith("org.") && !sources.containsKey(target)) {
            sources.put(target, source(target, "public class " + target.substring(target.lastIndexOf('.') + 1) + " {}"));
        }
        sources.put(name, source(name, "public class " + name.substring(name.lastIndexOf('.') + 1) + " { " + target + " dependency; }"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(sources.size());
        assertViolation(fixture, name, detail);
        if (!targetRelative.startsWith("connector.")) {
            ArchRule direction = targetRelative.startsWith("org.")
                ? ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_WEB : ArchitectureRules.LAYER_DIRECTION;
            assertThat(direction.evaluate(fixture).getFailureReport().toString()).contains(name, target);
            assertThatThrownBy(() -> direction.check(fixture)).isInstanceOf(AssertionError.class);
        }
    }


    static Stream<Arguments> wrongDirections() {
        return Stream.of(
            Arguments.of("infra.BadAdapter", "agent.purge.application.AgentPurgeWriter", "domain 과 infra"),
            Arguments.of("domain.BadPolicy", "agent.purge.application.AgentPurgeWriter", "domain 과 infra"),
            Arguments.of("presentation.BadController", "agent.purge.infra.Store", "presentation 은 infra"),
            Arguments.of("domain.WebPolicy", "agent.purge.presentation.Controller", "domain 은 웹"),
            Arguments.of("domain.HttpPolicy", "org.springframework.http.HttpStatus", "domain 은 웹"),
            Arguments.of("application.BadUseCase", "connector.application.ConnectorAgentPurge", "참여 구현"));
    }


    static Stream<String> names() {
        return NAMES.stream().sorted();
    }


    private static ArchRule rule() {
        return ArchitectureRules.agentPurgeContractPlacement(ROOT);
    }


    private static String fqn(String name) {
        return APPLICATION + (name.equals("AgentPurgeOutcome") ? "model." : "") + name;
    }


    private static Map<String, String> sources() {
        Map<String, String> sources = new HashMap<>();
        for (String name : NAMES) {
            String declaration = switch (name) {
                case "AgentPurgeParticipant" -> "public interface AgentPurgeParticipant {}";
                case "AgentPurgeOutcome" -> "public enum AgentPurgeOutcome { PURGED, WAITING, GONE }";
                default -> "@org.springframework.stereotype.Component public class " + name + " {}";
            };
            sources.put(fqn(name), source(fqn(name), declaration));
        }
        return sources;
    }


    private static String source(String name, String declaration) {
        return "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + declaration;
    }


    private static void assertViolation(JavaClasses fixture, String... details) {
        ArchRule rule = rule();
        var result = rule.evaluate(fixture);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains(rule.getDescription()).contains(details);
        assertThatThrownBy(() -> rule.check(fixture)).isInstanceOf(AssertionError.class);
    }
}
