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
    @ValueSource(strings = {"embedded", "getter", "inheritedField", "inheritedGetter", "idclass", "inheritedIdClass"})
    @DisplayName("EmbeddedId 와 IdClass 는 엔티티와 같은 패키지면 허용하고 다른 패키지면 거절한다")
    void checksPersistentIdPackage(String kind) throws IOException {
        for (boolean samePackage : new boolean[] {true, false}) {
            String idPackage = samePackage ? ENTITY : ROOT + ".crypto.domain";
            String idName = idPackage + ".KeyId";
            boolean idClass = kind.endsWith("IdClass") || kind.equals("idclass");
            String declaration = idClass ? "@jakarta.persistence.Id long id;"
                : kind.equals("getter") || kind.equals("inheritedGetter")
                    ? "@jakarta.persistence.EmbeddedId public " + idName + " getId() { return null; }"
                    : "@jakarta.persistence.EmbeddedId " + idName + " id;";
            String annotation = idClass ? "@jakarta.persistence.IdClass(" + idName + ".class) " : "";
            Map<String, String> sources = owner(ENTITY, "UserDataKey");
            boolean inherited = kind.startsWith("inherited");
            if (inherited) {
                String basePackage = ROOT + ".crypto.domain";
                sources.put(basePackage + ".Base", "package " + basePackage + "; " + annotation
                    + "@jakarta.persistence.MappedSuperclass public class Base { " + declaration + " }");
                sources.put(ENTITY + ".Extra", entitySource(ENTITY, "Extra", "")
                    .replace("class Extra", "class Extra extends " + basePackage + ".Base"));
            } else {
                sources.put(ENTITY + ".Extra", entitySource(ENTITY, "Extra", declaration)
                    .replace("@jakarta.persistence.Entity", annotation + "@jakarta.persistence.Entity"));
            }
            sources.put(idName, "package " + idPackage + "; "
                + (!idClass ? "@jakarta.persistence.Embeddable " : "")
                + "public class KeyId implements java.io.Serializable { public long id; }");
            JavaClasses fixture = ArchitectureRulesTest.compileFixture(
                temporary.resolve(kind + samePackage), sources);
            assertThat(fixture).hasSize(inherited ? 4 : 3);
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


    @Test
    @DisplayName("정상 소유자를 남겨도 다른 기능의 중첩 Table 중복을 거절한다")
    void rejectsNestedDuplicateTableOwner() throws IOException {
        for (boolean duplicate : new boolean[] {false, true}) {
            Map<String, String> sources = owner(ENTITY, "UserDataKey");
            String other = ROOT + ".other.domain";
            sources.put(other + ".Container", source(other, "Container",
                "@jakarta.persistence.Entity @jakarta.persistence.Table(name=\""
                    + (duplicate ? "user_data_key" : "other_table") + "\") public static class Duplicate {}"));
            JavaClasses fixture = ArchitectureRulesTest.compileFixture(temporary.resolve("nested" + duplicate), sources);
            assertThat(fixture).hasSize(3);
            if (duplicate) {
                assertThat(entities().evaluate(fixture).getFailureReport().toString())
                    .contains("실제 2", other + ".Container$Duplicate");
            } else {
                entities().check(fixture);
            }
        }
    }


    @ParameterizedTest
    @ValueSource(strings = {"Dto", "Model", "Kind", "Config", "Policy"})
    @DisplayName("공개 중첩 DTO 와 model 과 enum 과 config 와 policy 를 거절한다")
    void rejectsPublicNestedRoles(String name) throws IOException {
        Map<String, String> sources = owner(ENTITY, "UserDataKey");
        String nested = name.equals("Kind") ? "public enum Kind { ONE }"
            : name.equals("Dto") || name.equals("Model") ? "public record " + name + "() {}"
                : "public static class " + name + " {}";
        sources.put(ENTITY + ".Extra", entitySource(ENTITY, "Extra", nested));
        JavaClasses fixture = compile(sources);
        assertThat(fixture).hasSize(3);
        entities().check(fixture);
        assertThat(ids().evaluate(fixture).getFailureReport().toString()).contains(ENTITY + ".Extra$" + name);
    }


    @ParameterizedTest
    @ValueSource(strings = {"policy", "entity", "id"})
    @DisplayName("entity 하위 패키지는 일반 값과 엔티티와 실제 ID 모두 거절한다")
    void rejectsEntitySubpackages(String kind) throws IOException {
        Map<String, String> sources = owner(ENTITY, "UserDataKey");
        String child = ENTITY + ".child";
        sources.put(child + ".Value", kind.equals("entity") ? entitySource(child, "Value", "")
            : source(child, "Value", ""));
        if (kind.equals("id")) {
            sources.put(ENTITY + ".Extra", entitySource(ENTITY, "Extra",
                "@jakarta.persistence.EmbeddedId " + child + ".Value id;"));
        }
        JavaClasses fixture = compile(sources);
        assertThat(fixture).hasSize(kind.equals("id") ? 3 : 2);
        assertThat(ids().evaluate(fixture).getFailureReport().toString()).contains(child + ".Value");
    }


    @ParameterizedTest
    @ValueSource(strings = {"orphan", "privateEmbeddable", "privateEntity", "packageNested"})
    @DisplayName("참조되지 않은 Embeddable 과 private 영속 타입과 비공개 구현이 아닌 중첩 값을 거절한다")
    void rejectsValuesDisguisedAsPersistentIds(String kind) throws IOException {
        Map<String, String> sources = owner(ENTITY, "UserDataKey");
        String annotation = kind.equals("privateEntity") ? "@jakarta.persistence.Entity "
            : "@jakarta.persistence.Embeddable ";
        String target;
        if (kind.equals("orphan")) {
            sources.put(ENTITY + ".Policy", "package " + ENTITY + "; " + annotation + "public class Policy {}");
            target = ENTITY + ".Policy";
        } else {
            String body = kind.equals("packageNested") ? "static class Value {}"
                : annotation + "private static class Value {}";
            sources.put(ENTITY + ".Extra", entitySource(ENTITY, "Extra", body));
            target = ENTITY + ".Extra$Value";
        }
        JavaClasses fixture = compile(sources);
        assertThat(fixture).hasSize(kind.equals("orphan") ? 2 : 3);
        assertThat(ids().evaluate(fixture).getFailureReport().toString()).contains(target);
    }


    @Test
    @DisplayName("다른 기능만 import 하면 ID 단독 규칙도 실질 대상 0개를 거절한다")
    void rejectsMissingCryptoEntitiesInIdRule() throws IOException {
        JavaClasses fixture = compile(Map.of(ROOT + ".other.domain.Other",
            entitySource(ROOT + ".other.domain", "Other", "")));
        assertThat(fixture).hasSize(1);
        assertThat(ids().evaluate(fixture).getFailureReport().toString())
            .contains("EMBEDDED_IDS_WITH_ENTITIES", "crypto 엔티티 검사 대상이 0개");
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
