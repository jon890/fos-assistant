package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
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

/** crypto 의 엔티티 위치와 영속 ID 역할을 운영 규칙과 같은 조건으로 검사한다. */
@Tag("architecture")
class UserDataKeyPlacementTest {

    private static final String ROOT = "com.bifos.assistant.fixture";
    private static final String ENTITY = ROOT + ".crypto.domain.entity";

    @TempDir
    Path temporary;

    @Test
    @DisplayName("운영 crypto 엔티티의 위치와 영속 ID 역할을 기준 파일 없이 검사한다")
    void checksProductionPlacement() {
        assertThat(ArchitectureRulesTest.MAIN.stream()
                .filter(item -> item.getSimpleName().equals("UserDataKey"))).hasSize(1);
        ArchitectureRules.ENTITIES_IN_OWNED_ENTITY_PACKAGE.check(ArchitectureRulesTest.MAIN);
        ArchitectureRules.EMBEDDED_IDS_WITH_ENTITIES.check(ArchitectureRulesTest.MAIN);
    }

    @Test
    @DisplayName("정상 엔티티와 새 엔티티는 entity 에 두고 정책과 projection 과 enum 은 밖에 둔다")
    void acceptsOwnedEntitiesAndOtherRoles() throws IOException {
        Map<String, String> sources = owner(ENTITY, "UserDataKey");
        sources.put(ENTITY + ".Extra", entitySource(ENTITY, "Extra", ""));
        sources.put(ROOT + ".crypto.domain.Policy", source(ROOT + ".crypto.domain", "Policy", ""));
        sources.put(ROOT + ".crypto.infra.Projection", source(ROOT + ".crypto.infra", "Projection", ""));
        sources.put(ROOT + ".crypto.domain.type.Kind", "package " + ROOT + ".crypto.domain.type; public enum Kind { ONE }");
        // 아직 옮기지 않은 다른 기능의 엔티티에는 crypto 위치를 강제하지 않는다.
        sources.put(ROOT + ".other.domain.Other", entitySource(ROOT + ".other.domain", "Other", ""));
        JavaClasses fixture = compile(sources);
        assertThat(fixture).hasSize(6);
        entities().check(fixture);
        ids().check(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"old", "other", "renamed", "missing", "duplicate", "empty", "unannotated"})
    @DisplayName("옛 위치와 다른 기능과 표명 유지 이동과 누락과 중복과 빈 선택을 거절한다")
    void rejectsOwnerContractViolations(String scenario) throws IOException {
        Map<String, String> sources = switch (scenario) {
            case "old" -> owner(ROOT + ".crypto.domain", "UserDataKey");
            case "other" -> owner(ROOT + ".other.domain.entity", "UserDataKey");
            case "renamed" -> owner(ROOT + ".other.domain.entity", "RenamedKey");
            case "missing", "empty" -> new HashMap<>();
            case "unannotated" -> new HashMap<>(Map.of(ENTITY + ".UserDataKey", source(ENTITY, "UserDataKey", "")));
            default -> owner(ENTITY, "UserDataKey");
        };
        if (scenario.equals("duplicate")) {
            sources.put(ROOT + ".other.domain.entity.UserDataKey",
                    entitySource(ROOT + ".other.domain.entity", "UserDataKey", ""));
        }
        if (scenario.equals("missing")) {
            sources.put(ENTITY + ".Extra", entitySource(ENTITY, "Extra", ""));
        }
        if (scenario.equals("empty")) {
            sources.put(ROOT + ".Empty", source(ROOT, "Empty", ""));
        }
        JavaClasses fixture = compile(sources);
        assertThat(fixture).hasSize(scenario.equals("duplicate") ? 2 : 1);
        String report = entities().evaluate(fixture).getFailureReport().toString();
        assertThat(report).contains("ENTITIES_IN_OWNED_ENTITY_PACKAGE", ROOT,
                scenario.equals("renamed") ? "RenamedKey" : "UserDataKey");
    }

    @Test
    @DisplayName("전체 클래스가 비어 있어도 raw 규칙은 통과하지 않는다")
    void rejectsEmptyImport() {
        JavaClasses empty = new ClassFileImporter().importClasses();
        assertThat(empty).isEmpty();
        assertThatThrownBy(() -> entities().check(empty)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ids().check(empty)).isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".domain", ".infra"})
    @DisplayName("새 crypto 엔티티도 domain.entity 밖이면 거절한다")
    void rejectsNewEntityOutsideEntityPackage(String suffix) throws IOException {
        Map<String, String> sources = owner(ENTITY, "UserDataKey");
        String misplaced = ROOT + ".crypto" + suffix;
        sources.put(misplaced + ".Extra", entitySource(misplaced, "Extra", ""));
        JavaClasses fixture = compile(sources);
        assertThat(fixture).hasSize(2);
        assertThat(entities().evaluate(fixture).getFailureReport().toString())
                .contains("ENTITIES_IN_OWNED_ENTITY_PACKAGE", misplaced + ".Extra");
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "idclass"})
    @DisplayName("EmbeddedId 와 IdClass 는 엔티티와 같은 패키지면 허용하고 다른 패키지면 거절한다")
    void checksPersistentIdPackage(String kind) throws IOException {
        for (boolean samePackage : new boolean[] {true, false}) {
            String idPackage = samePackage ? ENTITY : ROOT + ".crypto.domain";
            String idName = idPackage + ".KeyId";
            String declaration = kind.equals("embedded")
                    ? "@jakarta.persistence.EmbeddedId " + idName + " id;"
                    : "@jakarta.persistence.Id long id;";
            Map<String, String> sources = owner(ENTITY, "UserDataKey");
            String entity = entitySource(ENTITY, "Extra", declaration);
            if (kind.equals("idclass")) {
                entity = entity.replace("@jakarta.persistence.Entity",
                        "@jakarta.persistence.IdClass(" + idName + ".class) @jakarta.persistence.Entity");
            }
            sources.put(ENTITY + ".Extra", entity);
            sources.put(idName, "package " + idPackage + "; "
                    + (kind.equals("embedded") ? "@jakarta.persistence.Embeddable " : "")
                    + "public class KeyId implements java.io.Serializable { public long id; }");
            JavaClasses fixture = ArchitectureRulesTest.compileFixture(
                    temporary.resolve(kind + samePackage), sources);
            assertThat(fixture).hasSize(3);
            entities().check(fixture);
            if (samePackage) {
                ids().check(fixture);
            } else {
                assertThat(ids().evaluate(fixture).getFailureReport().toString())
                        .contains("EMBEDDED_IDS_WITH_ENTITIES", ENTITY + ".Extra", idName);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Policy", "Projection", "Event", "Kind"})
    @DisplayName("entity 패키지에 정책과 projection 과 event 와 enum 을 두면 거절한다")
    void rejectsNonPersistentTopLevelTypes(String name) throws IOException {
        Map<String, String> sources = owner(ENTITY, "UserDataKey");
        sources.put(ENTITY + "." + name, name.equals("Kind")
                ? "package " + ENTITY + "; public enum Kind { ONE }" : source(ENTITY, name, ""));
        JavaClasses fixture = compile(sources);
        assertThat(fixture).hasSize(2);
        entities().check(fixture);
        assertThat(ids().evaluate(fixture).getFailureReport().toString())
                .contains("EMBEDDED_IDS_WITH_ENTITIES", ENTITY + "." + name);
    }

    @Test
    @DisplayName("엔티티 안의 private 중첩 구현 값은 별도 영속 역할로 세지 않는다")
    void acceptsPrivateNestedImplementation() throws IOException {
        Map<String, String> sources = owner(ENTITY, "UserDataKey");
        sources.put(ENTITY + ".Extra", entitySource(ENTITY, "Extra", "private static class Value {}"));
        JavaClasses fixture = compile(sources);
        assertThat(fixture).hasSize(3);
        entities().check(fixture);
        ids().check(fixture);
    }

    private JavaClasses compile(Map<String, String> sources) throws IOException {
        return ArchitectureRulesTest.compileFixture(temporary, sources);
    }

    private static ArchRule entities() {
        return ArchitectureRules.entitiesInOwnedEntityPackage(ROOT);
    }

    private static ArchRule ids() {
        return ArchitectureRules.embeddedIdsWithEntities(ROOT);
    }

    private static Map<String, String> owner(String packageName, String name) {
        return new HashMap<>(Map.of(packageName + "." + name,
                entitySource(packageName, name, "").replace("@jakarta.persistence.Entity",
                        "@jakarta.persistence.Table(name=\"user_data_key\") @jakarta.persistence.Entity")));
    }

    private static String entitySource(String packageName, String name, String body) {
        return "package " + packageName + "; @jakarta.persistence.Entity public class " + name + " { " + body + " }";
    }

    private static String source(String packageName, String name, String body) {
        return "package " + packageName + "; public class " + name + " { " + body + " }";
    }
}
