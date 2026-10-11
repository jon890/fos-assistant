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

/** 서비스 역할과 HTTP graph 를 실제 규칙과 같은 조건으로 확인한다. */
@Tag("architecture")
class AgentMemorySettingServicePlacementTest {
    @TempDir
    Path temporary;


    @Test
    @DisplayName("새 서비스와 공개 결과 및 HTTP 중첩 DTO 의 정상 배치를 허용한다")
    void acceptsRoles() throws IOException {
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, roleSources());
        assertThat(fixture).hasSize(5);
        ArchitectureRules.featureApplicationRole(ROOT).check(fixture);
    }


    @ParameterizedTest
    @MethodSource("invalidRoles")
    @DisplayName("서비스·결과·설정·영속 타입의 잘못된 역할을 거절한다")
    void rejectsRole(String relative, String declaration, String detail) throws IOException {
        Map<String, String> sources = roleSources();
        String name = ROOT + "." + relative;
        sources.put(name, source(name, declaration));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture.stream().filter(type -> type.getName().equals(name)).toList()).hasSize(1);
        assertViolation(ArchitectureRules.featureApplicationRole(ROOT), fixture, name, detail);
    }


    static Stream<Arguments> invalidRoles() {
        return Stream.of(
            Arguments.of("agent.memory.application.model.BadService", "@org.springframework.stereotype.Service public class BadService {}", "Service"),
            Arguments.of("memory.agent.config.BadComponent", "@org.springframework.stereotype.Component public class BadComponent {}", "Component"),
            Arguments.of("agent.memory.domain.BadService", "@org.springframework.stereotype.Service public class BadService {}", "Service"),
            Arguments.of("agent.memory.infra.BadService", "@org.springframework.stereotype.Service public class BadService {}", "Service"),
            Arguments.of("memory.agent.presentation.BadService", "@org.springframework.stereotype.Service public class BadService {}", "Service"),
            Arguments.of("agent.memory.application.BadValue", "public record BadValue(String value) {}", "application.model"),
            Arguments.of("memory.agent.application.BadResult", "public enum BadResult { OK }", "application.model"),
            Arguments.of("memory.agent.application.BadProperties", "@org.springframework.boot.context.properties.ConfigurationProperties(\"fixture\") public record BadProperties(String value) {}", "config"),
            Arguments.of("agent.memory.config.BadHelper", "public class BadHelper { int state; }", "상태 helper"),
            Arguments.of("agent.memory.application.BadEntity", "@jakarta.persistence.Entity public class BadEntity {}", "domain.entity"));
    }


    @Test
    @DisplayName("HTTP mapped graph 의 DTO 를 model 로 옮겨도 HTTP 역할 위반을 찾는다")
    void rejectsHttpDtoInModel() throws IOException {
        Map<String, String> sources = roleSources();
        String dtos = ROOT + ".memory.agent.presentation.ExtraDtos";
        sources.remove(dtos);
        String wrong = ROOT + ".memory.agent.application.model.HttpValue";
        sources.put(wrong, source(wrong, "public record HttpValue(String value) {}"));
        sources.replaceAll((name, body) -> body.replace(dtos + ".HttpValue", wrong));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(4);
        assertViolation(ArchitectureRules.featureApplicationRole(ROOT), fixture, wrong, "HTTP graph");
    }


    @Test
    @DisplayName("같은 presentation 의 다른 outer 로 빠진 확정 HTTP record 도 거절한다")
    void rejectsDetachedOwnedHttpDto() throws IOException {
        Map<String, String> sources = AgentMemoryCollectionServicePlacementTest.sources();
        String dtos = ROOT + ".memory.agent.presentation.AgentMemoryDtos";
        sources.compute(dtos, (key, body) -> body.replace("public record AgentMemoryGrantBody() {}", ""));
        String detached = ROOT + ".memory.agent.presentation.OtherDtos";
        sources.put(detached, source(detached, "public class OtherDtos { public record AgentMemoryGrantBody() {} }"));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture.stream().filter(type -> type.getSimpleName().equals("AgentMemoryGrantBody")).toList()).hasSize(1);
        assertViolation(ArchitectureRules.movedContractOwnership(ROOT), fixture, detached + "$AgentMemoryGrantBody", "AgentMemoryDtos");
    }


    @Test
    @DisplayName("서비스 입출력 graph 는 record 가 아닌 공개 결과도 model 배치를 검사한다")
    void rejectsPlainServiceValueOutsideModel() throws IOException {
        Map<String, String> sources = roleSources();
        String old = ROOT + ".agent.memory.application.model.ExtraResult";
        String wrong = ROOT + ".agent.memory.application.ExtraResult";
        sources.remove(old);
        sources.put(wrong, source(wrong, "public class ExtraResult { public String value; }"));
        sources.replaceAll((name, body) -> body.replace(old, wrong));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture.stream().filter(type -> type.getName().equals(wrong)).toList()).hasSize(1);
        assertViolation(ArchitectureRules.featureApplicationRole(ROOT), fixture, wrong, "application.model");
    }


    @Test
    @DisplayName("domain 정책과 infra projection 및 올바른 설정과 영속 타입을 공개 결과로 올리지 않는다")
    void acceptsOtherLayerRoles() throws IOException {
        Map<String, String> sources = roleSources();
        Map<String, String> declarations = Map.of(
            "agent.memory.domain.Policy", "public record Policy(String value) {}",
            "memory.agent.infra.Projection", "public record Projection(String value) {}",
            "memory.agent.config.Settings", "@org.springframework.boot.context.properties.ConfigurationProperties(\"fixture\") public record Settings(String value) {}",
            "agent.memory.domain.entity.Row", "@jakarta.persistence.Entity public class Row {}",
            "agent.memory.application.Helper", "class Helper { int state; }");
        declarations.forEach((relative, declaration) -> sources.put(ROOT + "." + relative, source(ROOT + "." + relative, declaration)));
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary, sources);
        assertThat(fixture).hasSize(5 + declarations.size());
        ArchitectureRules.featureApplicationRole(ROOT).check(fixture);
    }


    private static Map<String, String> roleSources() {
        Map<String, String> sources = new HashMap<>();
        String service = ROOT + ".agent.memory.application.AgentMemoryExtraService";
        String value = ROOT + ".agent.memory.application.model.ExtraResult";
        String dtos = ROOT + ".memory.agent.presentation.ExtraDtos";
        String controller = ROOT + ".memory.agent.presentation.ExtraController";
        sources.put(service, source(service, "@org.springframework.stereotype.Service public class AgentMemoryExtraService { public " + value + " run() { return null; } }"));
        sources.put(value, source(value, "public record ExtraResult(String value) {}"));
        sources.put(dtos, source(dtos, "public class ExtraDtos { public record HttpValue(String value) {} }"));
        sources.put(controller, source(controller, "public class ExtraController { @org.springframework.web.bind.annotation.GetMapping public java.util.List<" + dtos + ".HttpValue> get() { return null; } }"));
        return sources;
    }
}
