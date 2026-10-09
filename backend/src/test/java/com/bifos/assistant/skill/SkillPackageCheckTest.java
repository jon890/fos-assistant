package com.bifos.assistant.skill;

import static com.bifos.assistant.skill.application.SkillService.MAX_CHARS_PER_FILE;
import static com.bifos.assistant.skill.application.SkillService.MAX_FILES;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.DESCRIPTION_TOO_LONG;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.FILE_TOO_LARGE;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.FRONTMATTER_INVALID;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NAME_INVALID;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NESTED_SKILL_MD;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NOT_TEXT;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NO_BODY;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NO_SKILL_MD;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.PATH_NOT_ALLOWED;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.SECRET_REQUEST;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.SECRET_VALUE;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.TOO_MANY_FILES;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.TOTAL_TOO_LARGE;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.UNSAFE_ENTRY;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.skill.application.CheckedSkillPackage;
import com.bifos.assistant.skill.application.ReceivedSkillPackage;
import com.bifos.assistant.skill.application.SkillPackageCheck;
import com.bifos.assistant.skill.application.SkillPackageEntry;
import com.bifos.assistant.skill.application.SkillPackageProblem;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.domain.SkillFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 받은 스킬 묶음을 에이전트와 무관한 규칙으로 검사할 때 빼는 항목, 감싼 폴더, 경로, 글 파일, 크기, 비밀값, 앞머리의 문제를 모두
 * 모으는지 본다.
 *
 * <p>비밀값처럼 보이는 글은 소스에 상수로 두지 않고 시험 안에서 접두사와 반복 문자로 만든다. 저장소의 비밀 검사가 이 파일을
 * 잡지 않게 하려는 것이다.
 */
class SkillPackageCheckTest {

    private static final String SKILL_MD = skillMd("pdf-forms", "PDF 양식을 채운다", "# 쓰는 법\n양식을 연다.\n");

    private final SkillPackageCheck check = new SkillPackageCheck();

    @Test
    @DisplayName("감싼 폴더를 한 번 벗기고 숨은 항목과 __MACOSX 아래 항목을 뺀 목록으로 본다")
    void unwrapsFolderAndIgnoresHiddenEntries() {
        CheckedSkillPackage checked = check.check(received(
                text("my-skill/SKILL.md", SKILL_MD),
                text("my-skill/scripts/run.sh", "#!/bin/sh\necho hi\n"),
                text("my-skill/references/guide.md", "# 안내\n"),
                text("__MACOSX/my-skill/._SKILL.md", "x"),
                text(".DS_Store", "x"),
                text("my-skill/.git/config", "[core]\n")));

        assertThat(checked.problems()).isEmpty();
        assertThat(checked.name()).isEqualTo("pdf-forms");
        assertThat(checked.description()).isEqualTo("PDF 양식을 채운다");
        assertThat(checked.skillMd()).isEqualTo(SKILL_MD);
        assertThat(checked.files())
                .containsExactly(
                        new SkillFile("references/guide.md", "# 안내\n"),
                        new SkillFile("scripts/run.sh", "#!/bin/sh\necho hi\n"));
        assertThat(checked.hasScripts()).isTrue();
        assertThat(checked.ignored())
                .containsExactlyInAnyOrder("__MACOSX/my-skill/._SKILL.md", ".DS_Store", "my-skill/.git/config");
    }

    @Test
    @DisplayName("맨 위 SKILL.md 가 없으면 NO_SKILL_MD 하나로 끝낸다")
    void reportsOnlyMissingSkillMd() {
        CheckedSkillPackage checked = check.check(
                received(text("first/SKILL.md", SKILL_MD), text("second/notes.md", "# 메모\n"), bytes("bin/run", 0)));

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(NO_SKILL_MD, null));
        assertThat(checked.files()).isEmpty();
        assertThat(checked.skillMd()).isNull();
        assertThat(checked.name()).isNull();
    }

    @Test
    @DisplayName("감싼 폴더는 한 번만 벗기므로 두 겹 안의 SKILL.md 는 맨 위가 아니다")
    void unwrapsOnlyOnce() {
        CheckedSkillPackage checked = check.check(received(text("outer/inner/SKILL.md", SKILL_MD)));

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(NO_SKILL_MD, null));
    }

    @Test
    @DisplayName("맨 위가 아닌 SKILL.md, 경로 규칙에 맞지 않는 파일, 글이 아닌 파일은 그 경로의 문제다")
    void reportsPathAndTextProblemsWithPath() {
        byte[] pngHead = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D};
        CheckedSkillPackage checked = check.check(received(
                text("SKILL.md", SKILL_MD),
                text("references/SKILL.md", SKILL_MD),
                text("bin/run.sh", "#!/bin/sh\n"),
                new SkillPackageEntry("assets/logo.png", pngHead),
                text("templates/form.md", "# 양식\n")));

        assertThat(checked.problems())
                .containsExactly(
                        new SkillPackageProblem(PATH_NOT_ALLOWED, "bin/run.sh"),
                        new SkillPackageProblem(NESTED_SKILL_MD, "references/SKILL.md"),
                        new SkillPackageProblem(NOT_TEXT, "assets/logo.png"));
        assertThat(checked.files()).containsExactly(new SkillFile("templates/form.md", "# 양식\n"));
        assertThat(checked.hasScripts()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"skill.md", "Skill.md"})
    @DisplayName("맨 위에서 대소문자만 다른 SKILL.md 는 다른 자리의 SKILL.md 가 아니라 PATH_NOT_ALLOWED 다")
    void rejectsTopLevelSkillMdWithOtherCase(String path) {
        CheckedSkillPackage checked = check.check(received(text("SKILL.md", SKILL_MD), text(path, SKILL_MD)));

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(PATH_NOT_ALLOWED, path));
        assertThat(checked.files()).isEmpty();
    }

    @Test
    @DisplayName("NUL 이 든 파일은 UTF-8 로 읽혀도 글이 아니다")
    void rejectsTextWithNul() {
        CheckedSkillPackage checked =
                check.check(received(text("SKILL.md", SKILL_MD), text("references/a.md", "앞\u0000뒤")));

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(NOT_TEXT, "references/a.md"));
        assertThat(checked.files()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"references/a, references/a/b.md", "references/Guide.md, references/guide.md"})
    @DisplayName("하나씩은 경로 규칙에 맞아도 함께 놓일 수 없는 두 경로는 묶음 전체의 PATH_NOT_ALLOWED 다")
    void rejectsPathsThatCannotSitTogether(String first, String second) {
        CheckedSkillPackage checked =
                check.check(received(text("SKILL.md", SKILL_MD), text(first, "가\n"), text(second, "나\n")));

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(PATH_NOT_ALLOWED, null));
    }

    @Test
    @DisplayName("참고 파일이 상한과 같으면 받고 하나 더 많으면 TOO_MANY_FILES 다")
    void limitsFileCount() {
        List<SkillPackageEntry> atLimit = new ArrayList<>(List.of(text("SKILL.md", SKILL_MD)));
        for (int index = 0; index < MAX_FILES; index++) {
            atLimit.add(text("references/r" + index + ".md", "# " + index + "\n"));
        }
        List<SkillPackageEntry> overLimit = new ArrayList<>(atLimit);
        overLimit.add(text("references/extra.md", "# 하나 더\n"));

        CheckedSkillPackage accepted = check.check(new ReceivedSkillPackage(atLimit, null));
        CheckedSkillPackage rejected = check.check(new ReceivedSkillPackage(overLimit, null));

        assertThat(accepted.problems()).isEmpty();
        assertThat(accepted.files()).hasSize(MAX_FILES);
        assertThat(rejected.problems()).containsExactly(new SkillPackageProblem(TOO_MANY_FILES, null));
    }

    @Test
    @DisplayName("글자 수 상한을 넘는 파일은 FILE_TOO_LARGE 이고 files 에 넣지 않는다")
    void rejectsFileOverCharLimit() {
        CheckedSkillPackage checked = check.check(received(
                text("SKILL.md", SKILL_MD),
                text("references/fit.md", "a".repeat(MAX_CHARS_PER_FILE)),
                text("references/big.md", "a".repeat(MAX_CHARS_PER_FILE + 1))));

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(FILE_TOO_LARGE, "references/big.md"));
        assertThat(checked.files()).extracting(SkillFile::path).containsExactly("references/fit.md");
    }

    @Test
    @DisplayName("글 파일의 UTF-8 바이트 합계가 상한을 넘으면 TOTAL_TOO_LARGE 다")
    void rejectsTotalOverByteLimit() {
        List<SkillPackageEntry> entries = new ArrayList<>(List.of(text("SKILL.md", SKILL_MD)));
        // 파일마다 글자 수 상한 안이지만 11개를 합치면 1 MiB 를 넘는다.
        for (int index = 0; index < 11; index++) {
            entries.add(text("references/r" + index + ".md", "a".repeat(MAX_CHARS_PER_FILE)));
        }

        CheckedSkillPackage checked = check.check(new ReceivedSkillPackage(entries, null));

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(TOTAL_TOO_LARGE, null));
    }

    @Test
    @DisplayName("서비스 접두사로 시작하는 key 와 개인 키 머리 줄은 그 파일의 SECRET_VALUE 다")
    void detectsSecretValues() {
        String apiKey = "sk-ant-" + "a".repeat(30);
        String privateKeyLine = "-----BEGIN " + "OPENSSH PRIVATE KEY" + "-----";
        String skillMdWithKey = skillMd("pdf-forms", "PDF 양식을 채운다", "키는 " + apiKey + " 이다.\n");

        CheckedSkillPackage checked = check.check(received(
                text("SKILL.md", skillMdWithKey),
                text("references/key.txt", "앞 줄\n" + privateKeyLine + "\nabc\n"),
                text("references/clean.md", "# 깨끗하다\n")));

        assertThat(checked.problems())
                .containsExactly(
                        new SkillPackageProblem(SECRET_VALUE, "SKILL.md"),
                        new SkillPackageProblem(SECRET_VALUE, "references/key.txt"));
    }

    @Test
    @DisplayName("낱말 안의 sk- 는 앞 경계가 없어 비밀값이 아니다")
    void ignoresPrefixInsideWord() {
        String runner = "task-runner-" + "a".repeat(30);

        CheckedSkillPackage checked =
                check.check(received(text("SKILL.md", SKILL_MD), text("references/run.md", "도구는 " + runner + "\n")));

        assertThat(checked.problems()).isEmpty();
    }

    @Test
    @DisplayName("앞머리에 비밀 요청 칸이 있으면 SKILL.md 의 SECRET_REQUEST 다")
    void rejectsSecretRequest() {
        String requesting = "---\nname: pdf-forms\ndescription: PDF 양식을 채운다\n"
                + "required_environment_variables:\n  - name: EXAMPLE_TOKEN\n---\n# 본문\n";

        CheckedSkillPackage checked = check.check(received(text("SKILL.md", requesting)));

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(SECRET_REQUEST, "SKILL.md"));
        assertThat(checked.name()).isEqualTo("pdf-forms");
    }

    @Test
    @DisplayName("앞머리를 읽지 못하면 FRONTMATTER_INVALID 이고 이름과 설명은 비어 있다")
    void reportsUnreadableFrontmatter() {
        CheckedSkillPackage checked = check.check(received(text("SKILL.md", "# 앞머리 없이 본문만\n")));

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(FRONTMATTER_INVALID, "SKILL.md"));
        assertThat(checked.name()).isNull();
        assertThat(checked.description()).isNull();
        assertThat(checked.skillMd()).isEqualTo("# 앞머리 없이 본문만\n");
    }

    @Test
    @DisplayName("이름 규칙, 본문, 설명 1024자를 함께 어기면 SKILL.md 의 문제가 모두 나온다")
    void reportsAllFrontmatterProblems() {
        String broken = "---\nname: Bad_Name\ndescription: " + "가".repeat(1025) + "\n---\n";

        CheckedSkillPackage checked = check.check(received(text("SKILL.md", broken)));

        assertThat(checked.problems())
                .containsExactly(
                        new SkillPackageProblem(NAME_INVALID, "SKILL.md"),
                        new SkillPackageProblem(NO_BODY, "SKILL.md"),
                        new SkillPackageProblem(DESCRIPTION_TOO_LONG, "SKILL.md"));
    }

    @Test
    @DisplayName("여러 단계의 문제가 함께 있으면 처음 하나로 끝내지 않고 모두 낸다")
    void collectsProblemsAcrossSteps() {
        String requesting =
                "---\nname: pdf-forms\ndescription: 설명\nrequired_credential_files:\n  - a.json\n---\n# 본문\n";

        CheckedSkillPackage checked = check.check(received(
                text("SKILL.md", requesting),
                text("bin/run.sh", "#!/bin/sh\n"),
                bytes("assets/blob.bin", 0xFF),
                text("references/key.md", "ghp_" + "b".repeat(36))));

        assertThat(checked.problems())
                .containsExactly(
                        new SkillPackageProblem(PATH_NOT_ALLOWED, "bin/run.sh"),
                        new SkillPackageProblem(NOT_TEXT, "assets/blob.bin"),
                        new SkillPackageProblem(SECRET_VALUE, "references/key.md"),
                        new SkillPackageProblem(SECRET_REQUEST, "SKILL.md"));
    }

    @Test
    @DisplayName("문제는 50개까지만 모은다")
    void capsProblemsAtFifty() {
        List<SkillPackageEntry> entries = new ArrayList<>(List.of(text("SKILL.md", SKILL_MD)));
        for (int index = 0; index < 60; index++) {
            entries.add(text("bin/f" + index + ".sh", "x\n"));
        }

        CheckedSkillPackage checked = check.check(new ReceivedSkillPackage(entries, null));

        assertThat(checked.problems()).hasSize(SkillPackageCheck.MAX_PROBLEMS);
        assertThat(checked.problems()).allMatch(problem -> problem.reason() == PATH_NOT_ALLOWED);
    }

    @Test
    @DisplayName("받기에서 난 문제가 있으면 그 하나만 낸다")
    void passesThroughReceiveProblem() {
        ReceivedSkillPackage failed = ReceivedSkillPackage.failed(UNSAFE_ENTRY, "evil/link");

        CheckedSkillPackage checked = check.check(failed);

        assertThat(checked.problems()).containsExactly(new SkillPackageProblem(UNSAFE_ENTRY, "evil/link"));
        assertThat(checked.files()).isEmpty();
        assertThat(checked.ignored()).isEmpty();
        assertThat(checked.name()).isNull();
    }

    @Test
    @DisplayName("지문은 SKILL.md 와 파일을 경로 차례로 경로 NUL 내용 NUL 로 이은 글의 SHA-256 이다")
    void digestJoinsFilesInPathOrder() {
        SkillBundle bundle = new SkillBundle(
                "pdf-forms",
                "본문",
                List.of(new SkillFile("templates/t.md", "티"), new SkillFile("references/r.md", "알")));

        assertThat(bundle.digest())
                .isEqualTo(Sha256.hex("SKILL.md\0본문\0references/r.md\0알\0templates/t.md\0티\0"))
                .hasSize(64);
    }

    @Test
    @DisplayName("지문은 파일 순서와 관계없이 같고 내용 한 글자가 바뀌면 달라진다")
    void digestIgnoresOrderButNotContent() {
        SkillFile first = new SkillFile("references/a.md", "가나다");
        SkillFile second = new SkillFile("scripts/run.sh", "echo hi");
        SkillBundle bundle = new SkillBundle("pdf-forms", SKILL_MD, List.of(first, second));
        SkillBundle reordered = new SkillBundle("pdf-forms", SKILL_MD, List.of(second, first));
        SkillBundle edited =
                new SkillBundle("pdf-forms", SKILL_MD, List.of(new SkillFile("references/a.md", "가나라"), second));

        assertThat(reordered.digest()).isEqualTo(bundle.digest());
        assertThat(edited.digest()).isNotEqualTo(bundle.digest());
    }

    private static String skillMd(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
    }

    private static ReceivedSkillPackage received(SkillPackageEntry... entries) {
        return new ReceivedSkillPackage(List.of(entries), null);
    }

    private static SkillPackageEntry text(String path, String content) {
        return new SkillPackageEntry(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static SkillPackageEntry bytes(String path, int value) {
        return new SkillPackageEntry(path, new byte[] {(byte) value});
    }
}
