package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.domain.SkillFile;
import com.bifos.assistant.skill.infra.PreviousSkill;
import com.bifos.assistant.skill.infra.SkillProperties;
import com.bifos.assistant.skill.infra.SkillStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
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
        return new SkillStore(new SkillProperties(root.toString(), AGENT_ROOT + "/", 3, null), Clock.systemUTC());
    }

    private static Map<String, SkillBundle> skills(String... names) {
        LinkedHashMap<String, SkillBundle> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(
                    name,
                    new SkillBundle(
                            name,
                            WEEKLY_MD.replace("weekly-plan", name),
                            List.of(new SkillFile("references/guide.md", "안내 " + name))));
        }
        return result;
    }

    @Test
    @DisplayName("쓰기는 새 버전을 만들고 표식 전에는 지금 버전이 옛 것이다")
    void writeMakesNewVersionAndCurrentVersionIsOldBeforeMarker(@TempDir Path root) {
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
        assertThat(store.currentVersion(PROFILE))
                .as("둘째에 표식을 쓰기 전까지 첫째가 지금 버전이다")
                .contains(first);
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
    @DisplayName("표식 없는 버전은 지금 버전보다 새 것만 올린 스킬로 읽고 같은 이름이면 더 새 것을 쓴다")
    void readsOnlyNewerUnmarkedVersionAsUploadedAndUsesNewerOfSameName(@TempDir Path root) {
        SkillStore store = storeAt(root);
        String older = store.writeVersion(PROFILE, skills("stale"));
        String current = store.writeVersion(PROFILE, skills("weekly-plan"));
        store.markPublished(PROFILE, current);
        assertThat(store.readPending(PROFILE)).as("표식 없는 새 버전이 없으면 비어 있다").isEmpty();

        store.writeVersion(PROFILE, skills("weekly-plan", "shopping"));
        Map<String, SkillBundle> newer = new LinkedHashMap<>(skills("shopping"));
        newer.put(
                "shopping",
                new SkillBundle("shopping", WEEKLY_MD.replace("weekly-plan", "shopping") + "고침", List.of()));
        store.writeVersion(PROFILE, newer);

        Map<String, SkillBundle> pending = store.readPending(PROFILE);
        assertThat(pending).as("지금 버전보다 오래된 %s 의 stale 은 보지 않는다", older).containsOnlyKeys("shopping", "weekly-plan");
        assertThat(pending.get("shopping").skillMd()).as("같은 이름이면 더 새 버전의 것").endsWith("고침");
        assertThat(store.readCurrent(PROFILE)).containsOnlyKeys("weekly-plan");
    }

    @Test
    @DisplayName("지금 버전이 없어도 표식 없는 버전에 스킬이 있으면 올린 스킬이 있다고 본다")
    void seesUploadedSkillInUnmarkedVersionEvenWithoutCurrentVersion(@TempDir Path root) {
        SkillStore store = storeAt(root);
        assertThat(store.hasUploadedSkills(PROFILE)).isFalse();

        store.writeVersion(PROFILE, skills("shopping"));

        assertThat(store.currentVersion(PROFILE)).isEmpty();
        assertThat(store.readPending(PROFILE)).containsOnlyKeys("shopping");
        assertThat(store.hasUploadedSkills(PROFILE)).isTrue();
    }

    @Test
    @DisplayName("prune 은 표식 있는 최근 3개만 남기고 게시한 버전보다 오래된 표식 없는 것을 지운다")
    void pruneKeepsRecentThreeMarkedAndDeletesOlderUnmarked(@TempDir Path root) {
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
        assertThat(profileDir.resolve(newerUnpublished))
                .as("게시한 버전보다 새 표식 없는 버전은 두지 않는다")
                .exists();
        assertThat(store.currentVersion(PROFILE)).contains(v5);
    }

    @Test
    @DisplayName("상위 경로와 절대 경로와 루트 밖 경로를 거절한다")
    void rejectsParentAbsoluteAndOutsideRootPaths(@TempDir Path root) {
        SkillStore store = storeAt(root);

        assertValidation(() -> store.writeVersion(
                PROFILE,
                Map.of(
                        "weekly-plan",
                        new SkillBundle("weekly-plan", WEEKLY_MD, List.of(new SkillFile("references/../x.md", "x"))))));
        assertValidation(() -> store.writeVersion(
                PROFILE,
                Map.of(
                        "weekly-plan",
                        new SkillBundle("weekly-plan", WEEKLY_MD, List.of(new SkillFile("/etc/passwd", "x"))))));
        assertValidation(() -> store.writeVersion(
                PROFILE,
                Map.of(
                        "weekly-plan",
                        new SkillBundle("weekly-plan", WEEKLY_MD, List.of(new SkillFile("bin/run.sh", "x"))))));
        assertValidation(() -> store.writeVersion(PROFILE, Map.of("..", new SkillBundle("..", WEEKLY_MD, List.of()))));
        assertValidation(() -> store.writeVersion("../other", skills("weekly-plan")));
        assertValidation(() -> store.currentVersion("/abs"));
        assertValidation(() -> store.agentPath(PROFILE, "../v0000000000000-aaaa"));
        assertValidation(() -> store.deleteAll(".."));
        assertThat(root.resolve(PROFILE)).as("거절한 쓰기는 아무것도 남기지 않는다").doesNotExist();
    }

    @Test
    @DisplayName("심볼릭 링크는 읽지 않는다")
    void doesNotReadSymlinks(@TempDir Path root, @TempDir Path outside) throws IOException {
        SkillStore store = storeAt(root);
        String version = published(store);
        Files.writeString(outside.resolve("secret.md"), "밖의 파일");
        Path versionDir = root.resolve(PROFILE).resolve(version);
        Files.createSymbolicLink(
                versionDir.resolve("weekly-plan").resolve("references").resolve("leak.md"),
                outside.resolve("secret.md"));

        assertThatThrownBy(() -> store.readCurrent(PROFILE))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));

        Files.delete(versionDir.resolve("weekly-plan").resolve("references").resolve("leak.md"));
        Files.createSymbolicLink(versionDir.resolve("linked"), outside);

        assertThatThrownBy(() -> store.readCurrent(PROFILE))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("파일은 644 디렉터리는 755 로 쓴다")
    void writesFilesWith644AndDirectoriesWith755(@TempDir Path root) throws IOException {
        SkillStore store = storeAt(root);
        String version = published(store);
        Path versionDir = root.resolve(PROFILE).resolve(version);
        Path skillDir = versionDir.resolve("weekly-plan");

        for (Path dir : List.of(root.resolve(PROFILE), versionDir, skillDir, skillDir.resolve("references"))) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                    .as("디렉터리 %s", root.relativize(dir))
                    .isEqualTo("rwxr-xr-x");
        }
        for (Path file : List.of(
                skillDir.resolve("SKILL.md"),
                skillDir.resolve("references/guide.md"),
                versionDir.resolve(SkillStore.PUBLISHED_MARKER))) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .as("파일 %s", root.relativize(file))
                    .isEqualTo("rw-r--r--");
        }
    }

    @Test
    @DisplayName("discard 와 deleteAll 은 디렉터리를 지우고 없으면 지나간다")
    void discardAndDeleteAllRemoveDirectoryAndPassWhenAbsent(@TempDir Path root) {
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

    @Test
    @DisplayName("경로 규칙은 맨 위 글 파일과 네 디렉터리 아래 4조각까지를 받고 나머지를 거절한다")
    void filePathRuleAcceptsTopLevelTextAndFourDirectoriesUpToFourSegments() {
        for (String accepted : List.of(
                "FORMS.md",
                "notes.TXT",
                "references/API.md",
                "scripts/lib/util.py",
                "assets/a/b/c.txt",
                "references/" + "a".repeat(95) + "/" + "b".repeat(93))) {
            assertThatCode(() -> SkillStore.requireFilePath(accepted))
                    .as("받아야 하는 경로 %s", accepted)
                    .doesNotThrowAnyException();
        }
        for (String rejected : List.of(
                "notes.py",
                "SKILL.md",
                "skill.MD",
                "references/SKILL.md",
                "other/x.md",
                "scripts/a/b/c/d.py",
                "references/.hidden",
                "references/../x.md",
                "references//x.md",
                "references/" + "a".repeat(101),
                "references/" + "a".repeat(95) + "/" + "b".repeat(94),
                "")) {
            assertValidation(() -> SkillStore.requireFilePath(rejected));
        }
        assertValidation(() -> SkillStore.requireFilePath(null));
    }

    @Test
    @DisplayName("파일 묶음은 대소문자만 다른 두 경로와 다른 경로의 디렉터리인 경로를 거절한다")
    void fileSetRejectsCaseOnlyDuplicatesAndPathThatIsDirectoryOfAnother() {
        assertValidation(() -> SkillStore.requireFileSet(List.of("references/A.md", "references/a.md")));
        assertValidation(() -> SkillStore.requireFileSet(List.of("references/a", "references/a/b.md")));
        assertValidation(() -> SkillStore.requireFileSet(List.of("scripts/lib/util.py", "scripts/LIB")));
        assertThatCode(() -> SkillStore.requireFileSet(List.of("references/a.md", "references/ab/c.md", "FORMS.md")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("scripts 아래 파일만 755 로 쓰고 중첩 경로의 중간 디렉터리도 755 다")
    void writesScriptsWith755AndNestedDirectoriesWith755(@TempDir Path root) throws IOException {
        SkillStore store = storeAt(root);
        String version = store.writeVersion(
                PROFILE,
                Map.of(
                        "weekly-plan",
                        new SkillBundle(
                                "weekly-plan",
                                WEEKLY_MD,
                                List.of(
                                        new SkillFile("scripts/run.sh", "echo"),
                                        new SkillFile("scripts/lib/util.py", "print()"),
                                        new SkillFile("references/a.md", "안내")))));
        Path skillDir = root.resolve(PROFILE).resolve(version).resolve("weekly-plan");

        assertThat(permissions(skillDir.resolve("scripts/run.sh"))).isEqualTo("rwxr-xr-x");
        assertThat(permissions(skillDir.resolve("scripts/lib/util.py"))).isEqualTo("rwxr-xr-x");
        assertThat(permissions(skillDir.resolve("references/a.md"))).isEqualTo("rw-r--r--");
        for (Path dir : List.of(skillDir.resolve("scripts"), skillDir.resolve("scripts/lib"))) {
            assertThat(permissions(dir)).as("디렉터리 %s", skillDir.relativize(dir)).isEqualTo("rwxr-xr-x");
        }
    }

    @Test
    @DisplayName("읽기는 중첩 경로와 맨 위 글 파일을 경로 차례로 읽고 점 파일과 규칙 밖 파일은 읽지 않는다")
    void readsNestedAndTopLevelFilesInPathOrderAndSkipsDotAndOutOfRuleFiles(@TempDir Path root) throws IOException {
        SkillStore store = storeAt(root);
        List<SkillFile> files = List.of(
                new SkillFile("FORMS.md", "양식"),
                new SkillFile("assets/a/b/c.txt", "자산"),
                new SkillFile("scripts/lib/util.py", "print()"));
        String version =
                store.writeVersion(PROFILE, Map.of("weekly-plan", new SkillBundle("weekly-plan", WEEKLY_MD, files)));
        store.markPublished(PROFILE, version);
        Path skillDir = root.resolve(PROFILE).resolve(version).resolve("weekly-plan");
        Files.writeString(skillDir.resolve(".published"), "");
        Files.createDirectories(skillDir.resolve(".cache"));
        Files.writeString(skillDir.resolve(".cache/x.md"), "숨은 것");
        Files.writeString(skillDir.resolve("notes.py"), "규칙 밖");

        assertThat(store.readCurrent(PROFILE).get("weekly-plan").files()).containsExactlyElementsOf(files);
    }

    @Test
    @DisplayName("스킬 안의 링크는 경로 규칙 안이든 밖이든 읽기가 INTERNAL ERROR 다")
    void linkInsideSkillIsInternalErrorWhetherInsideOrOutsidePathRule(@TempDir Path root, @TempDir Path outside)
            throws IOException {
        SkillStore store = storeAt(root);
        String version = published(store);
        Path skillDir = root.resolve(PROFILE).resolve(version).resolve("weekly-plan");
        Files.writeString(outside.resolve("other.md"), "밖의 파일");

        Path inRule = skillDir.resolve("references/x.md");
        Files.createSymbolicLink(inRule, outside.resolve("other.md"));
        assertInternalError(() -> store.readCurrent(PROFILE));

        Files.delete(inRule);
        Files.createSymbolicLink(skillDir.resolve("bin"), outside);
        assertInternalError(() -> store.readCurrent(PROFILE));
    }

    @Test
    @DisplayName("이전 버전은 쓴 묶음과 시각을 다시 주고 두 번 쓰면 뒤의 것이 남으며 prune 뒤에도 남는다")
    void previousGivesBackBundleAndTimeAndLaterWinsAndSurvivesPrune(@TempDir Path root) {
        Instant firstAt = Instant.parse("2026-10-09T01:02:03.004Z");
        MutableClock clock = new MutableClock(firstAt);
        SkillStore store = new SkillStore(new SkillProperties(root.toString(), AGENT_ROOT, 3, null), clock);
        assertThat(store.readPrevious(PROFILE, "weekly-plan")).isEmpty();
        SkillBundle first =
                new SkillBundle("weekly-plan", WEEKLY_MD, List.of(new SkillFile("scripts/run.sh", "echo 1")));
        SkillBundle second = new SkillBundle("weekly-plan", WEEKLY_MD + "고침", List.of());

        store.writePrevious(PROFILE, first);
        assertThat(store.readPrevious(PROFILE, "weekly-plan")).contains(new PreviousSkill(first, firstAt));

        Instant secondAt = firstAt.plusSeconds(60);
        clock.now = secondAt;
        store.writePrevious(PROFILE, second);
        assertThat(store.readPrevious(PROFILE, "weekly-plan")).contains(new PreviousSkill(second, secondAt));
        assertThat(store.previousSavedAt(PROFILE, "weekly-plan")).contains(secondAt);
        assertThat(root.resolve(PROFILE)
                        .resolve(SkillStore.PREVIOUS_DIR)
                        .toFile()
                        .list())
                .as("바꾼 뒤 옮겨 둔 옛것과 임시 디렉터리가 남지 않는다")
                .containsExactly("weekly-plan");
        assertThat(root.resolve(PROFILE).resolve(SkillStore.PREVIOUS_DIR).resolve("weekly-plan/scripts"))
                .as("바뀐 이전 버전에는 첫째의 파일이 없다")
                .doesNotExist();

        String version = published(store);
        store.prune(PROFILE, version);
        assertThat(store.readPrevious(PROFILE, "weekly-plan"))
                .as("prune 은 이전 버전을 지우지 않는다")
                .isPresent();

        store.deletePrevious(PROFILE, "weekly-plan");
        assertThat(store.readPrevious(PROFILE, "weekly-plan")).isEmpty();
        store.deletePrevious(PROFILE, "weekly-plan");
    }

    @Test
    @DisplayName("clearVersions 는 버전과 이전 버전과 임시 디렉터리를 지우고 profile 디렉터리를 남긴다")
    void clearVersionsRemovesVersionsPreviousAndTempButKeepsProfileDirectory(@TempDir Path root) throws IOException {
        SkillStore store = storeAt(root);
        published(store);
        store.writeVersion(PROFILE, skills("weekly-plan"));
        store.writePrevious(PROFILE, skills("weekly-plan").get("weekly-plan"));
        Files.createDirectories(root.resolve(PROFILE).resolve(".tmp-left"));

        store.clearVersions(PROFILE);

        assertThat(root.resolve(PROFILE)).isDirectory().isEmptyDirectory();
        assertThat(store.currentVersion(PROFILE)).isEmpty();
        assertThat(store.readPrevious(PROFILE, "weekly-plan")).isEmpty();
        store.clearVersions("ua-absent");
    }

    @Test
    @DisplayName("이전 버전을 쓰면 앞선 쓰기가 남긴 old 와 tmp 디렉터리를 지운다")
    void writePreviousRemovesLeftoverOldAndTempDirectories(@TempDir Path root) throws IOException {
        SkillStore store = storeAt(root);
        Path previousRoot = root.resolve(PROFILE).resolve(SkillStore.PREVIOUS_DIR);
        for (String leftover : List.of(".old-x-abcd", ".tmp-x-abcd")) {
            Files.createDirectories(previousRoot.resolve(leftover));
            Files.writeString(previousRoot.resolve(leftover).resolve("SKILL.md"), "남은 것");
        }

        store.writePrevious(PROFILE, skills("weekly-plan").get("weekly-plan"));

        assertThat(previousRoot.toFile().list()).containsExactly("weekly-plan");
    }

    @Test
    @DisplayName("이전 버전 시각은 남긴 시각 파일만 읽어 주고 없으면 빈 값이다")
    void previousSavedAtReadsOnlySavedAtFile(@TempDir Path root) throws IOException {
        SkillStore store = storeAt(root);
        assertThat(store.previousSavedAt(PROFILE, "weekly-plan")).isEmpty();
        Path skillDir = root.resolve(PROFILE).resolve(SkillStore.PREVIOUS_DIR).resolve("weekly-plan");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve(SkillStore.SAVED_AT), "1791507723004\n");

        assertThat(store.previousSavedAt(PROFILE, "weekly-plan"))
                .as("SKILL.md 가 없어도 시각은 준다")
                .contains(Instant.ofEpochMilli(1_791_507_723_004L));

        Files.writeString(skillDir.resolve(SkillStore.SAVED_AT), "어제");
        assertInternalError(() -> store.previousSavedAt(PROFILE, "weekly-plan"));
    }

    @Test
    @DisplayName(".previous 가 링크면 deletePrevious 와 clearVersions 가 INTERNAL ERROR 이고 링크 너머를 지우지 않는다")
    void deletePreviousAndClearVersionsRejectLinkedPreviousAndKeepLinkTarget(@TempDir Path root, @TempDir Path outside)
            throws IOException {
        SkillStore store = storeAt(root);
        published(store);
        Path outsideSkill = outside.resolve("weekly-plan");
        Files.createDirectories(outsideSkill);
        Files.writeString(outsideSkill.resolve("SKILL.md"), "밖의 파일");
        Path previousRoot = root.resolve(PROFILE).resolve(SkillStore.PREVIOUS_DIR);
        Files.createSymbolicLink(previousRoot, outside);

        assertInternalError(() -> store.deletePrevious(PROFILE, "weekly-plan"));
        assertInternalError(() -> store.clearVersions(PROFILE));
        assertInternalError(() -> store.previousSavedAt(PROFILE, "weekly-plan"));

        assertThat(outsideSkill.resolve("SKILL.md")).as("링크 너머는 그대로다").exists();
        assertThat(Files.isSymbolicLink(previousRoot)).isTrue();
        assertThat(store.currentVersion(PROFILE))
                .as("거절한 clearVersions 는 버전도 지우지 않는다")
                .isPresent();
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static void assertInternalError(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    /** 이전 버전을 남긴 시각을 정해 두는 시계다. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static String published(SkillStore store) {
        String version = store.writeVersion(PROFILE, skills("weekly-plan"));
        store.markPublished(PROFILE, version);
        return version;
    }

    private static void assertValidation(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
