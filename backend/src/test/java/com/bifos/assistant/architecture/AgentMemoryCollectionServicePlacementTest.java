package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
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

/** Memory 소유 계약을 루트 전체에서 검사하고 누락·중복과 옛 위치 복귀를 막는다. */
@Tag("architecture")
class AgentMemoryCollectionServicePlacementTest {
    static final String ROOT = "com.bifos.assistant.fixture";

    @TempDir
    Path temporary;


    @Test
    @DisplayName("운영 Memory 계약은 각각 하나이고 raw 소유·역할·방향 검사를 통과한다")
    void checksProductionContracts() {
        for (String name : ArchitectureRules.memoryContractOwners().keySet()) {
            assertThat(ArchitectureRulesTest.MAIN.stream().filter(type -> type.getSimpleName().equals(name)).toList())
                .as("운영 소유 타입 %s", name).hasSize(1);
        }
        ArchitectureRules.MOVED_CONTRACT_OWNERSHIP.check(ArchitectureRulesTest.MAIN);
        ArchitectureRules.FEATURE_APPLICATION_ROLE.check(ArchitectureRulesTest.MAIN);
        ArchitectureRules.AGENT_MEMORY_HAS_NO_UPWARD_MEMORY_DEPENDENCY.check(ArchitectureRulesTest.MAIN);
        ArchitectureRules.FEATURE_LAYER_COVERAGE.check(ArchitectureRulesTest.MAIN);
    }


    @Test
    @DisplayName("정상 Memory 소유 fixture 는 전체 계약을 선택하고 허용한다")
    void acceptsOwnedFixture() throws IOException {
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources());
        for (String name : ArchitectureRules.memoryContractOwners().keySet()) {
            assertThat(fixture.stream().filter(type -> type.getSimpleName().equals(name)).toList()).hasSize(1);
        }
        ArchitectureRules.movedContractOwnership(ROOT).check(fixture);
    }


    @ParameterizedTest
    @MethodSource("ownedNames")
    @DisplayName("소유 계약 하나가 누락되면 raw 검사가 실패한다")
    void rejectsMissingType(String name) throws IOException {
        Map<String, String> sources = sources();
        String location = ROOT + "." + ArchitectureRules.memoryContractOwners().get(name);
        if (location.contains("$")) {
            sources.compute(location.substring(0, location.indexOf('$')), (key, body) -> body.replace("public record " + name + "() {}", ""));
        } else {
            sources.remove(location);
        }
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture.stream().filter(type -> type.getSimpleName().equals(name)).toList()).isEmpty();
        assertViolation(ArchitectureRules.movedContractOwnership(ROOT), fixture, name, "대상 수가 0");
    }


    @ParameterizedTest
    @MethodSource("ownedNames")
    @DisplayName("다른 기능에도 같은 이름이 있으면 단일 소유 검사가 실패한다")
    void rejectsDuplicateType(String name) throws IOException {
        Map<String, String> sources = sources();
        String duplicate = ROOT + ".other.application." + name;
        sources.put(duplicate, source(duplicate, "public class " + name + " {}"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture.stream().filter(type -> type.getSimpleName().equals(name)).toList()).hasSize(2);
        assertViolation(ArchitectureRules.movedContractOwnership(ROOT), fixture, duplicate, "대상 수가 2");
    }


    @ParameterizedTest
    @MethodSource("wrongPlacements")
    @DisplayName("옛 위치와 다른 기능 및 application 직하로 돌아간 계약을 거절한다")
    void rejectsWrongPlacement(String name, String destination) throws IOException {
        Map<String, String> sources = sources();
        String old = ROOT + "." + ArchitectureRules.memoryContractOwners().get(name);
        String moved = ROOT + "." + destination + "." + name;
        sources.put(moved, sources.remove(old).replace(old.substring(0, old.lastIndexOf('.')),
            moved.substring(0, moved.lastIndexOf('.'))));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture.stream().filter(type -> type.getSimpleName().equals(name)).toList()).hasSize(1);
        assertViolation(ArchitectureRules.movedContractOwnership(ROOT), fixture, moved, "에 있어야 한다");
    }


    static Stream<Arguments> wrongPlacements() {
        return Stream.of(
            Arguments.of("AgentMemoryCollectionService", "agent.application"),
            Arguments.of("AgentMemoryGrants", "agent.application"),
            Arguments.of("AgentMemoryGrants", "agent.memory.application"),
            Arguments.of("AgentMemorySettingService", "memory.application"),
            Arguments.of("AgentMemorySettingController", "memory.presentation"),
            Arguments.of("AgentMemorySetting", "memory.application.model"),
            Arguments.of("AgentMemorySetting", "other.application.model"),
            Arguments.of("AgentMemoryDtos", "memory.presentation"));
    }


    @Test
    @DisplayName("소유 타입 전체가 없거나 운영 루트 선택이 비어도 실패한다")
    void rejectsEmptySelection() throws IOException {
        JavaClasses unrelated = ArchitectureRulesTest.compileFixture(temporary.resolve("unrelated"),
            Map.of(ROOT + ".Unrelated", source(ROOT + ".Unrelated", "public class Unrelated {}")));
        assertViolation(ArchitectureRules.movedContractOwnership(ROOT), unrelated, "대상 수가 0");
        JavaClasses outside = ArchitectureRulesTest.compileFixture(temporary.resolve("outside"),
            Map.of("outside.Unrelated", "package outside; public class Unrelated {}"));
        assertThatThrownBy(() -> ArchitectureRules.movedContractOwnership(ROOT).check(outside))
            .isInstanceOf(AssertionError.class);
    }


    static Stream<String> ownedNames() {
        return ArchitectureRules.memoryContractOwners().keySet().stream().sorted();
    }


    static Map<String, String> sources() {
        Map<String, String> sources = new HashMap<>();
        String dtos = ROOT + ".memory.agent.presentation.AgentMemoryDtos";
        StringBuilder nested = new StringBuilder("public final class AgentMemoryDtos { ");
        ArchitectureRules.memoryContractOwners().forEach((name, relative) -> {
            if (relative.contains("$")) {
                nested.append("public record ").append(name).append("() {} ");
            } else if (!name.equals("AgentMemoryDtos")) {
                String declaration = name.endsWith("Service") || name.endsWith("Controller")
                    ? "public class " + name + " {}" : "public record " + name + "() {}";
                sources.put(ROOT + "." + relative, source(ROOT + "." + relative, declaration));
            }
        });
        sources.put(dtos, source(dtos, nested.append('}').toString()));
        return sources;
    }


    static String source(String name, String declaration) {
        return "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + declaration;
    }


    static void assertViolation(ArchRule rule, JavaClasses fixture, String... details) {
        var result = rule.evaluate(fixture);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains(rule.getDescription()).contains(details);
        assertThatThrownBy(() -> rule.check(fixture)).isInstanceOf(AssertionError.class);
    }
}
