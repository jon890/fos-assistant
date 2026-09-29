package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillBundle;
import com.bifos.assistant.skill.application.SkillFile;
import com.bifos.assistant.skill.application.SkillProperties;
import com.bifos.assistant.skill.infra.SkillStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 버전 디렉터리를 쓰고 표식으로 지금 버전을 고르고 옛 버전을 지우는 규칙을 본다. */
class SkillStoreTest {

    private static final String PROFILE = "ua-skilltest";
    private static final String AGENT_ROOT = "/agent-side/skills";

    private static final String WEEKLY_MD = """
            ---
            name: weekly-plan
            description: 이번 주 계획을 세운다
            ---
            # 주간 계획
            """;

    private static SkillStore storeAt(Path root) {
        return new SkillStore(new SkillProperties(root.toString(), AGENT_ROOT + "/", 3));
    }

    private static Map<String, SkillBundle> skills(String... names) {
        java.util.LinkedHashMap<String, SkillBundle> result = new java.util.LinkedHashMap<>();
        for (String name : names) {
            result.put(name, new SkillBundle(name, WEEKLY_MD.replace("weekly-plan", name),
                    List.of(new SkillFile("references/guide.md", "안내 " + name))));
        }
        return result;
    }

    @Test
    void 쓰기는_새_버전을_만들고_표식_전에는_지금_버전이_옛_것이다(@TempDir Path root) {
        SkillStore store = storeAt(root);
        assertThat(store.currentVersion(PROFILE)).isEmpty();
        assertThat(store.readCurrent(PROFILE)).isEmpty();

        String first = store.writeVersion(PROFILE, skills("weekly-plan"));
        assertThat(first).matches("v[0-9]{13}-[a-z0-9]{4}");
        assertThat(store.currentVersion(PROFILE)).as("표식 전에는 지금 버전이 없다").isEmpty();

        store.markPublished(PROFILE, first);
        assertThat(store.currentVersion(PROFILE)).contains(first);

        String second = store.writeVersion(PROFILE, skills("weekly-plan", "shopping"));
        assertThat(second).isGreaterThan(first);
        assertThat(store.currentVersion(PROFILE)).as("둘째에 표식을 쓰기 전까지 첫째가 지금 버전이다").contains(first);
        assertThat(store.readCurrent(PROFILE)).containsOnlyKeys("weekly-plan");

        store.markPublished(PROFILE, second);
        assertThat(store.currentVersion(PROFILE)).contains(second);
        Map<String, SkillBundle> current = store.readCurrent(PROFILE);
        assertThat(current).containsOnlyKeys("shopping", "weekly-plan");
        assertThat(current.get("shopping").files())
                .containsExactly(new SkillFile("references/guide.md", "안내 shopping"));
        assertThat(store.agentPath(PROFILE, second)).isEqualTo(AGENT_ROOT + "/" + PROFILE + "/" + second);
        assertThat(store.hasUploadedSkills(PROFILE)).isTrue();
    }

    @Test
    void prune_은_표식_있는_최근_3개만_남기고_게시한_버전보다_오래된_표식_없는_것을_지운다(@TempDir Path root) {
        SkillStore store = storeAt(root);
        String v1 = published(store);
        String v2 = store.writeVersion(PROFILE, skills("weekly-plan"));
        String v3 = published(store);
        String v4 = published(store);
        String v5 = published(store);
        String newerUnpublished = store.writeVersion(PROFILE, skills("weekly-plan"));

        store.prune(PROFILE, v5);

        Path profileDir = root.resolve(PROFILE);
        assertThat(profileDir.resolve(v1)).as("네 번째로 오래된 표식 있는 버전").doesNotExist();
        assertThat(profileDir.resolve(v2)).as("게시한 버전보다 오래된 표식 없는 버전").doesNotExist();
        assertThat(profileDir.resolve(v3)).exists();
        assertThat(profileDir.resolve(v4)).exists();
        assertThat(profileDir.resolve(v5)).exists();
        assertThat(profileDir.resolve(newerUnpublished)).as("게시한 버전보다 새 표식 없는 버전은 두지 않는다").exists();
        assertThat(store.currentVersion(PROFILE)).contains(v5);
    }

    @Test
    void 상위_경로와_절대_경로와_루트_밖_경로를_거절한다(@TempDir Path root) {
        SkillStore store = storeAt(root);

        assertValidation(() -> store.writeVersion(PROFILE, Map.of("weekly-plan",
                new SkillBundle("weekly-plan", WEEKLY_MD, List.of(new SkillFile("references/../x.md", "x"))))));
        assertValidation(() -> store.writeVersion(PROFILE, Map.of("weekly-plan",
                new SkillBundle("weekly-plan", WEEKLY_MD, List.of(new SkillFile("/etc/passwd", "x"))))));
        assertValidation(() -> store.writeVersion(PROFILE, Map.of("weekly-plan",
                new SkillBundle("weekly-plan", WEEKLY_MD, List.of(new SkillFile("scripts/run.sh", "x"))))));
        assertValidation(() -> store.writeVersion(PROFILE, Map.of("..",
                new SkillBundle("..", WEEKLY_MD, List.of()))));
        assertValidation(() -> store.writeVersion("../other", skills("weekly-plan")));
        assertValidation(() -> store.currentVersion("/abs"));
        assertValidation(() -> store.agentPath(PROFILE, "../v0000000000000-aaaa"));
        assertValidation(() -> store.deleteAll(".."));
        assertThat(root.resolve(PROFILE)).as("거절한 쓰기는 아무것도 남기지 않는다").doesNotExist();
    }

    @Test
    void 심볼릭_링크는_읽지_않는다(@TempDir Path root, @TempDir Path outside) throws IOException {
        SkillStore store = storeAt(root);
        String version = published(store);
        Files.writeString(outside.resolve("secret.md"), "밖의 파일");
        Path versionDir = root.resolve(PROFILE).resolve(version);
        Files.createSymbolicLink(versionDir.resolve("weekly-plan").resolve("references").resolve("leak.md"),
                outside.resolve("secret.md"));

        assertThatThrownBy(() -> store.readCurrent(PROFILE))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));

        Files.delete(versionDir.resolve("weekly-plan").resolve("references").resolve("leak.md"));
        Files.createSymbolicLink(versionDir.resolve("linked"), outside);

        assertThatThrownBy(() -> store.readCurrent(PROFILE))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void 파일은_644_디렉터리는_755_로_쓴다(@TempDir Path root) throws IOException {
        SkillStore store = storeAt(root);
        String version = published(store);
        Path versionDir = root.resolve(PROFILE).resolve(version);
        Path skillDir = versionDir.resolve("weekly-plan");

        for (Path dir : List.of(root.resolve(PROFILE), versionDir, skillDir, skillDir.resolve("references"))) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                    .as("디렉터리 %s", root.relativize(dir))
                    .isEqualTo("rwxr-xr-x");
        }
        for (Path file : List.of(skillDir.resolve("SKILL.md"), skillDir.resolve("references/guide.md"),
                versionDir.resolve(SkillStore.PUBLISHED_MARKER))) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .as("파일 %s", root.relativize(file))
                    .isEqualTo("rw-r--r--");
        }
    }

    @Test
    void discard_와_deleteAll_은_디렉터리를_지우고_없으면_지나간다(@TempDir Path root) {
        SkillStore store = storeAt(root);
        String first = published(store);
        String second = store.writeVersion(PROFILE, skills("weekly-plan"));

        store.discard(PROFILE, second);
        assertThat(root.resolve(PROFILE).resolve(second)).doesNotExist();
        assertThat(store.currentVersion(PROFILE)).contains(first);
        store.discard(PROFILE, second);

        store.deleteAll(PROFILE);
        assertThat(root.resolve(PROFILE)).doesNotExist();
        assertThat(store.hasUploadedSkills(PROFILE)).isFalse();
        store.deleteAll(PROFILE);
    }

    private static String published(SkillStore store) {
        String version = store.writeVersion(PROFILE, skills("weekly-plan"));
        store.markPublished(PROFILE, version);
        return version;
    }

    private static void assertValidation(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
