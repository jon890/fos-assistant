package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
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

/** 운영 소유 조건과 공용 컴파일 fixture로 memory 저장 타입 경계를 검사한다. */
@Tag("architecture")
class MemoryPlacementTest {
    private static final String ROOT = "com.bifos.assistant.fixture";
    private static final String ENTITY = ROOT + ".memory.domain.entity";
    private static final Map<String, String> TABLES = Map.of(
        "Memory", "memory", "MemoryCapture", "memory_capture", "MemoryCollection", "memory_collection",
        "MemoryRevision", "memory_revision", "ServiceToken", "service_token", "ServiceTokenCollection", "service_token_collection");
    private static final Map<String, String> IDS = Map.of(
        "MemoryCollection", "MemoryCollectionId", "MemoryRevision", "MemoryRevisionId", "ServiceTokenCollection", "ServiceTokenCollectionId");

    @TempDir
    Path temporary;


    @Test
    @DisplayName("운영 아홉 저장 타입과 실제 ID 연결을 기준 파일 없이 검사한다")
    void checksProductionPlacement() {
        assertThat(ArchitectureRulesTest.MAIN.stream()
            .filter(item -> item.getPackageName().equals("com.bifos.assistant.memory.domain.entity"))).hasSize(9);
        ArchitectureRules.ENTITIES_IN_OWNED_ENTITY_PACKAGE.check(ArchitectureRulesTest.MAIN);
        ArchitectureRules.EMBEDDED_IDS_WITH_ENTITIES.check(ArchitectureRulesTest.MAIN);
    }


    @Test
    @DisplayName("정상 저장 타입과 정책 및 projection과 event는 각 역할 위치에서 허용한다")
    void acceptsOwnedPersistentTypes() throws IOException {
        Map<String, String> sources = owners();
        sources.put(ROOT + ".memory.domain.Policy", source(ROOT + ".memory.domain", "Policy"));
        sources.put(ROOT + ".memory.domain.Event", source(ROOT + ".memory.domain", "Event"));
        sources.put(ROOT + ".memory.infra.Projection", source(ROOT + ".memory.infra", "Projection"));
        sources.put(ROOT + ".other.domain.Other", entity(ROOT + ".other.domain", "Other", "other", ""));
        JavaClasses fixture = compile("valid", sources, 13);
        ArchitectureRules.entitiesInOwnedEntityPackage(ROOT, "memory").check(fixture);
        ArchitectureRules.embeddedIdsWithEntities(ROOT, "memory").check(fixture);
    }


    @ParameterizedTest
    @ValueSource(strings = {"Memory", "MemoryCapture", "MemoryCollection", "MemoryCollectionId", "MemoryRevision", "MemoryRevisionId",
        "ServiceToken", "ServiceTokenCollection", "ServiceTokenCollectionId"})
    @DisplayName("각 소유 타입의 옛 위치와 다른 기능 이동 및 누락과 중복을 거절한다")
    void rejectsEveryOwnerViolation(String name) throws IOException {
        for (String scenario : new String[] {"old", "other", "missing", "duplicate", "role", "renamed"}) {
            if (scenario.equals("renamed") && !TABLES.containsKey(name)) {
                continue;
            }
            Map<String, String> sources = owners();
            String original = sources.get(ENTITY + "." + name);
            if (!scenario.equals("duplicate")) {
                sources.remove(ENTITY + "." + name);
            }
            String destination = scenario.equals("old") ? ROOT + ".memory.domain" : ROOT + ".other.domain.entity";
            String movedName = scenario.equals("renamed") ? "Renamed" + name : name;
            if (scenario.equals("role")) {
                sources.put(ENTITY + "." + name, source(ENTITY, name));
            } else if (!scenario.equals("missing")) {
                sources.put(destination + "." + movedName, original
                    .replace("package " + ENTITY + ";", "package " + destination + ";")
                    .replace("class " + name + " ", "class " + movedName + " "));
            }
            if (IDS.containsValue(name) && !scenario.equals("duplicate") && !scenario.equals("role")) {
                String oldReference = "@jakarta.persistence.EmbeddedId " + ENTITY + "." + name + " id;";
                String newReference = scenario.equals("missing") ? ""
                    : "@jakarta.persistence.EmbeddedId " + destination + "." + name + " id;";
                sources.replaceAll((key, value) -> value.replace(oldReference, newReference));
            }
            JavaClasses fixture = compile(name + scenario, sources, scenario.equals("missing") ? 8 : scenario.equals("duplicate") ? 10 : 9);
            assertThat(ArchitectureRules.entitiesInOwnedEntityPackage(ROOT, "memory").evaluate(fixture).getFailureReport().toString())
                .as(name + " " + scenario).contains("ENTITIES_IN_OWNED_ENTITY_PACKAGE", ROOT, movedName);
        }
    }


    @Test
    @DisplayName("빈 소유 선택과 빈 import는 raw 규칙을 통과하지 않는다")
    void rejectsEmptySelections() throws IOException {
        JavaClasses fixture = compile("emptySelection", Map.of(ROOT + ".Empty", source(ROOT, "Empty")), 1);
        assertThat(ArchitectureRules.entitiesInOwnedEntityPackage(ROOT, "memory").evaluate(fixture).getFailureReport().getDetails())
            .hasSize(15);
        JavaClasses empty = new ClassFileImporter().importClasses();
        assertThat(empty).isEmpty();
        assertThatThrownBy(() -> ArchitectureRules.entitiesInOwnedEntityPackage(ROOT, "memory").check(empty))
            .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ArchitectureRules.embeddedIdsWithEntities(ROOT, "memory").check(empty))
            .isInstanceOf(AssertionError.class);
    }


    @ParameterizedTest
    @ValueSource(strings = {"MemoryCollection", "MemoryRevision", "ServiceTokenCollection"})
    @DisplayName("실제 EmbeddedId가 다른 패키지에 남으면 소유 엔티티와 ID를 함께 보고한다")
    void rejectsSeparatedEmbeddedIds(String name) throws IOException {
        Map<String, String> sources = owners();
        String id = IDS.get(name);
        String old = ROOT + ".memory.domain";
        sources.remove(ENTITY + "." + id);
        sources.put(old + "." + id, "package " + old + "; @jakarta.persistence.Embeddable public class " + id + " {}");
        sources.put(ENTITY + "." + name, entity(ENTITY, name, TABLES.get(name), "@jakarta.persistence.EmbeddedId " + old + "." + id + " id;"));
        JavaClasses fixture = compile(name, sources, 9);
        assertThat(ArchitectureRules.embeddedIdsWithEntities(ROOT, "memory").evaluate(fixture).getFailureReport().toString())
            .contains("EMBEDDED_IDS_WITH_ENTITIES", ENTITY + "." + name, old + "." + id);
    }


    @ParameterizedTest
    @ValueSource(strings = {"Policy", "Projection", "Event", "UnusedId", "Kind"})
    @DisplayName("entity 안의 정책과 projection 및 event와 미참조 Embeddable과 enum을 거절한다")
    void rejectsNonPersistentRoles(String name) throws IOException {
        Map<String, String> sources = owners();
        String body = name.equals("Kind") ? "package " + ENTITY + "; public enum Kind { ONE }"
            : source(ENTITY, name);
        if (name.equals("UnusedId")) {
            body = body.replace("public class", "@jakarta.persistence.Embeddable public class");
        }
        sources.put(ENTITY + "." + name, body);
        JavaClasses fixture = compile(name, sources, 10);
        ArchitectureRules.entitiesInOwnedEntityPackage(ROOT, "memory").check(fixture);
        assertThat(ArchitectureRules.embeddedIdsWithEntities(ROOT, "memory").evaluate(fixture).getFailureReport().toString())
            .contains("EMBEDDED_IDS_WITH_ENTITIES", ENTITY + "." + name);
    }


    @ParameterizedTest
    @ValueSource(strings = {"", ".domain", ".infra", ".domain.entity.child"})
    @DisplayName("새 memory 엔티티도 정확한 entity 패키지 밖이면 거절한다")
    void rejectsNewMisplacedEntity(String suffix) throws IOException {
        Map<String, String> sources = owners();
        String misplaced = ROOT + ".memory" + suffix;
        sources.put(misplaced + ".Extra", entity(misplaced, "Extra", "extra", ""));
        JavaClasses fixture = compile("extra", sources, 10);
        assertThat(ArchitectureRules.entitiesInOwnedEntityPackage(ROOT, "memory").evaluate(fixture).getFailureReport().toString())
            .contains("ENTITIES_IN_OWNED_ENTITY_PACKAGE", misplaced + ".Extra");
    }


    @Test
    @DisplayName("같은 표를 선언한 중첩 엔티티도 중복 소유로 거절한다")
    void rejectsNestedTableDuplicate() throws IOException {
        Map<String, String> sources = owners();
        sources.put(ROOT + ".other.Outer", "package " + ROOT + ".other; public class Outer { "
            + "@jakarta.persistence.Entity @jakarta.persistence.Table(name=\"memory\") public static class Copy {} }");
        JavaClasses fixture = compile("nested", sources, 11);
        assertThat(ArchitectureRules.entitiesInOwnedEntityPackage(ROOT, "memory").evaluate(fixture).getFailureReport().toString())
            .contains(ROOT + ".other.Outer$Copy", "memory Table 기대 수 1, 실제 2");
    }


    private JavaClasses compile(String scenario, Map<String, String> sources, int expected) throws IOException {
        JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary.resolve(scenario), sources);
        assertThat(fixture).hasSize(expected);
        return fixture;
    }


    private static Map<String, String> owners() {
        Map<String, String> sources = new HashMap<>();
        TABLES.forEach((name, table) -> sources.put(ENTITY + "." + name,
            entity(ENTITY, name, table, IDS.containsKey(name) ? "@jakarta.persistence.EmbeddedId " + ENTITY + "." + IDS.get(name) + " id;" : "")));
        IDS.values().forEach(id -> sources.put(ENTITY + "." + id,
            "package " + ENTITY + "; @jakarta.persistence.Embeddable public class " + id + " {}"));
        return sources;
    }


    private static String entity(String packageName, String name, String table, String body) {
        return "package " + packageName + "; @jakarta.persistence.Entity @jakarta.persistence.Table(name=\"" + table
            + "\") public class " + name + " { " + body + " }";
    }


    private static String source(String packageName, String name) {
        return "package " + packageName + "; public class " + name + " {}";
    }
}
