package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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

/** 관리자 업무와 HTTP 경계의 운영 소스, 정상 소스와 위치·역할·권한 회귀를 같은 raw 규칙으로 본다. */
@Tag("architecture")
class AgentAdminServicePlacementTest {

    private static final String ROOT = "com.bifos.assistant.fixture";
    private static final String SERVICE = ROOT + ".agent.admin.application.AgentAdminService";
    private static final String CONTROLLER = ROOT + ".agent.admin.presentation.AgentAdminController";
    private static final String DTOS = ROOT + ".agent.admin.presentation.AgentAdminDtos";
    private static final Set<String> OWNED_NAMES = Set.of(
            "AgentAdminService",
            "AgentCreateCommand",
            "AgentUpdateCommand",
            "AgentAdminController",
            "AgentAdminDtos",
            "CreateAgentRequest",
            "UpdateAgentRequest",
            "AdminAgentView");

    @TempDir
    Path temporary;

    @Test
    @DisplayName("실제 운영 소스의 관리자 타입 8개가 각각 하나이고 raw 배치 검사를 통과한다")
    void checksProductionOwnership() {
        JavaClasses production = ArchitectureRulesTest.MAIN;
        assertThat(production.stream()
                        .filter(type -> OWNED_NAMES.contains(type.getSimpleName()))
                        .toList())
                .hasSize(8);
        for (String name : OWNED_NAMES) {
            assertThat(production.stream()
                            .filter(type -> type.getSimpleName().equals(name))
                            .toList())
                    .as("운영 타입 %s 의 선택 수", name)
                    .hasSize(1);
        }
        ArchitectureRules.AGENT_ADMIN_APPLICATION_PLACEMENT.check(production);
        ArchitectureRules.AGENT_ADMIN_HTTP_CONTRACT.check(production);
    }

    @Test
    @DisplayName("정상 fixture 의 소유 타입 8개와 공유 lifecycle 호출을 선택하고 허용한다")
    void acceptsOwnedFixture() throws IOException {
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources());
        assertThat(fixture).hasSize(10);
        assertThat(fixture.stream()
                        .filter(type -> OWNED_NAMES.contains(type.getSimpleName()))
                        .toList())
                .hasSize(8);
        ArchitectureRules.agentAdminApplicationPlacement(ROOT).check(fixture);
        ArchitectureRules.agentAdminHttpContract(ROOT).check(fixture);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongPlacements")
    @DisplayName("원래 위치로 돌아간 소유 타입도 전체 루트에서 선택해 거절한다")
    void rejectsWrongPlacement(String name, String original, String destination, boolean http) throws IOException {
        Map<String, String> sources = sources();
        move(sources, original, destination);
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture.stream()
                        .filter(type -> type.getSimpleName().equals(name))
                        .toList())
                .hasSize(1);
        assertViolation(rule(http), fixture, destination, "에 있어야 한다");
    }

    static Stream<Arguments> wrongPlacements() {
        return Stream.of(
                Arguments.of("AgentAdminService", SERVICE, ROOT + ".agent.application.AgentAdminService", false),
                Arguments.of(
                        "AgentCreateCommand",
                        ROOT + ".agent.admin.application.model.AgentCreateCommand",
                        ROOT + ".agent.application.AgentCreateCommand",
                        false),
                Arguments.of(
                        "AgentUpdateCommand",
                        ROOT + ".agent.admin.application.model.AgentUpdateCommand",
                        ROOT + ".agent.application.AgentUpdateCommand",
                        false),
                Arguments.of(
                        "AgentAdminController", CONTROLLER, ROOT + ".agent.presentation.AgentAdminController", true),
                Arguments.of("AgentAdminDtos", DTOS, ROOT + ".agent.presentation.AgentAdminDtos", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRoles")
    @DisplayName("admin 경계의 잘못된 역할과 아래 층의 역방향 의존을 거절한다")
    void rejectsInvalidRole(String relativeName, String declaration, String detail) throws IOException {
        Map<String, String> sources = sources();
        String name = ROOT + "." + relativeName;
        sources.put(name, source(name, declaration));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(11);
        assertViolation(rule(false), fixture, name, detail);
    }

    static Stream<Arguments> invalidRoles() {
        return Stream.of(
                Arguments.of(
                        "agent.admin.application.BadEntity",
                        "@jakarta.persistence.Entity public class BadEntity {}",
                        "Entity"),
                Arguments.of(
                        "agent.admin.application.BadEmbeddable",
                        "@jakarta.persistence.Embeddable public class BadEmbeddable {}",
                        "Embeddable"),
                Arguments.of(
                        "agent.admin.application.BadRepository", "public interface BadRepository {}", "Repository"),
                Arguments.of(
                        "agent.admin.presentation.BadService",
                        "@org.springframework.stereotype.Service public class BadService {}",
                        "Service"),
                Arguments.of(
                        "agent.admin.application.BadValue",
                        "public record BadValue(String value) {}",
                        "application.model"),
                Arguments.of(
                        "agent.admin.application.BadProperties",
                        "@org.springframework.boot.context.properties.ConfigurationProperties(\"fixture\") public record BadProperties(String value) {}",
                        "config"),
                Arguments.of(
                        "agent.domain.BadPolicy",
                        "public class BadPolicy { " + SERVICE + " service; }",
                        "domain 과 infra"),
                Arguments.of(
                        "agent.infra.BadAdapter",
                        "public class BadAdapter { " + SERVICE + " service; }",
                        "domain 과 infra"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mappedMethods")
    @DisplayName("관리 mapped 메서드마다 requireAdmin 호출 제거를 거절한다")
    void rejectsMissingAuthorization(String method) throws IOException {
        Map<String, String> sources = sources();
        sources.compute(
                CONTROLLER,
                (name, source) -> source.replace(
                        "void " + method + "() { currentUser.requireAdmin();", "void " + method + "() {"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(10);
        assertViolation(rule(true), fixture, CONTROLLER, method + "()", "requireAdmin");
    }

    static Stream<String> mappedMethods() {
        return Stream.of("create", "list", "update");
    }

    @Test
    @DisplayName("HTTP record 를 Dtos 밖으로 옮기면 거절한다")
    void rejectsDetachedHttpRecord() throws IOException {
        Map<String, String> sources = sources();
        sources.compute(DTOS, (name, source) -> source.replace("public record AdminAgentView() {}", ""));
        String name = ROOT + ".agent.admin.presentation.AdminAgentView";
        sources.put(name, source(name, "public record AdminAgentView() {}"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(10);
        assertViolation(rule(true), fixture, name, "AgentAdminDtos");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedNames")
    @DisplayName("각 소유 타입을 빼면 대상 수 0으로 거절한다")
    void rejectsMissingOwnedType(String name) throws IOException {
        Map<String, String> sources = sources();
        if (Set.of("CreateAgentRequest", "UpdateAgentRequest", "AdminAgentView").contains(name)) {
            sources.compute(DTOS, (key, source) -> source.replace("public record " + name + "() {}", ""));
        } else {
            sources.entrySet().removeIf(entry -> entry.getKey().endsWith("." + name));
            if (name.equals("AgentAdminService")) {
                sources.compute(
                        CONTROLLER,
                        (key, source) ->
                                source.replace(SERVICE + " service;", "").replace("service.execute();", ""));
            }
        }
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture.stream()
                        .filter(type -> type.getSimpleName().equals(name))
                        .toList())
                .isEmpty();
        assertViolation(rule(isHttp(name)), fixture, name, "대상 수가 0");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedNames")
    @DisplayName("각 소유 타입을 원래 경계에도 두면 대상 수 2로 거절한다")
    void rejectsDuplicateOwnedType(String name) throws IOException {
        Map<String, String> sources = sources();
        String duplicate = ROOT + ".agent.old." + name;
        sources.put(duplicate, source(duplicate, "public class " + name + " {}"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture.stream()
                        .filter(type -> type.getSimpleName().equals(name))
                        .toList())
                .hasSize(2);
        assertViolation(rule(isHttp(name)), fixture, duplicate, "대상 수가 2");
    }

    static Stream<String> ownedNames() {
        return OWNED_NAMES.stream().sorted();
    }

    @Test
    @DisplayName("소유 타입이 하나도 없거나 전체 루트가 비어도 raw 검사가 실패한다")
    void rejectsEmptySelection() throws IOException {
        String name = ROOT + ".Unrelated";
        JavaClasses unrelated = ArchitectureRulesTest.compileFixture(
                temporary.resolve("unrelated"), Map.of(name, source(name, "public class Unrelated {}")));
        assertThat(unrelated).hasSize(1);
        assertViolation(rule(false), unrelated, "AgentAdminService", "대상 수가 0");
        assertViolation(rule(true), unrelated, "AgentAdminController", "대상 수가 0");
        JavaClasses empty = ArchitectureRulesTest.compileFixture(
                temporary.resolve("outside"),
                Map.of("outside.Unrelated", "package outside; public class Unrelated {}"));
        assertThatThrownBy(() -> rule(false).check(empty)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> rule(true).check(empty)).isInstanceOf(AssertionError.class);
    }

    private static boolean isHttp(String name) {
        return !Set.of("AgentAdminService", "AgentCreateCommand", "AgentUpdateCommand")
                .contains(name);
    }

    private static ArchRule rule(boolean http) {
        return http
                ? ArchitectureRules.agentAdminHttpContract(ROOT)
                : ArchitectureRules.agentAdminApplicationPlacement(ROOT);
    }

    private static void assertViolation(ArchRule rule, JavaClasses fixture, String... details) {
        var result = rule.evaluate(fixture);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString())
                .contains(rule.getDescription())
                .contains(details);
        assertThatThrownBy(() -> rule.check(fixture)).isInstanceOf(AssertionError.class);
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new HashMap<>();
        String lifecycle = ROOT + ".agent.application.AgentLifecycleService";
        String auth = ROOT + ".shared.auth.CurrentUserProvider";
        sources.put(lifecycle, source(lifecycle, "public class AgentLifecycleService { public void execute() {} }"));
        sources.put(auth, source(auth, "public class CurrentUserProvider { public void requireAdmin() {} }"));
        sources.put(SERVICE, source(SERVICE, """
                import org.springframework.stereotype.Service;
                @Service
                public class AgentAdminService {
                    %s lifecycle;
                    public void execute() { lifecycle.execute(); }
                }
                """.formatted(lifecycle)));
        for (String name : List.of("AgentCreateCommand", "AgentUpdateCommand")) {
            String fqn = ROOT + ".agent.admin.application.model." + name;
            sources.put(fqn, source(fqn, "public record " + name + "() {}"));
        }
        sources.put(DTOS, source(DTOS, """
                public final class AgentAdminDtos {
                    public record CreateAgentRequest() {}
                    public record UpdateAgentRequest() {}
                    public record AdminAgentView() {}
                }
                """));
        sources.put(CONTROLLER, source(CONTROLLER, """
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PatchMapping;
                @RestController
                @RequestMapping("/api/v1/admin/agents")
                public class AgentAdminController {
                    %s service;
                    %s currentUser;
                    @PostMapping
                    public void create() { currentUser.requireAdmin(); service.execute(); }
                    @GetMapping
                    public void list() { currentUser.requireAdmin(); service.execute(); }
                    @PatchMapping("/{code}")
                    public void update() { currentUser.requireAdmin(); service.execute(); }
                }
                """.formatted(SERVICE, auth)));
        return sources;
    }

    private static String source(String name, String declaration) {
        return "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + declaration;
    }

    private static void move(Map<String, String> sources, String original, String destination) {
        String source = sources.remove(original);
        sources.put(
                destination,
                source.replace(
                        original.substring(0, original.lastIndexOf('.')),
                        destination.substring(0, destination.lastIndexOf('.'))));
        sources.replaceAll((name, body) -> body.replace(original, destination));
    }
}
