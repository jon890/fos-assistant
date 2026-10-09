package com.bifos.assistant.skill;

import static com.bifos.assistant.skill.application.model.SkillPackageChange.ADDED;
import static com.bifos.assistant.skill.application.model.SkillPackageChange.CHANGED;
import static com.bifos.assistant.skill.application.model.SkillPackageChange.REMOVED;
import static com.bifos.assistant.skill.application.model.SkillPackageChange.SAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillDetail;
import com.bifos.assistant.skill.application.SkillFileInput;
import com.bifos.assistant.skill.application.SkillPackageFile;
import com.bifos.assistant.skill.application.SkillPackagePreview;
import com.bifos.assistant.skill.application.SkillPackageProblem;
import com.bifos.assistant.skill.application.SkillPackageService;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.application.model.SkillPackageReason;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.domain.SkillFile;
import com.bifos.assistant.skill.infra.PreviousSkill;
import com.bifos.assistant.skill.infra.PreviousSkillStore;
import com.bifos.assistant.skill.infra.SkillStore;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 스킬 묶음의 미리보기가 저장하지 않고 바뀜과 문제를 주는지, 올리기가 같은 판정과 지문 확인을 거쳐 기존 저장 경로로 저장하는지 본다.
 *
 * <p>{@code SkillServiceTest} 처럼 테스트 클래스에 트랜잭션을 두지 않는다. 대시보드의 스킬 경로와 도구 목록만 대역이고, 버전
 * 디렉터리는 {@code application-test.yml} 의 경로에 실제로 쓴다. 시험 zip 은 시험 안에서 만든다.
 */
@BackendIntegrationTest
class SkillPackageServiceTest {

    private static final String OWNED = "skill-pkg-owned";
    private static final String OWNED_PROFILE = "skill-pkg-owned-profile";
    private static final String GROUP = "skill-pkg-group";
    private static final String GROUP_PROFILE = "skill-pkg-group-profile";

    private static final CurrentUser OWNER = new CurrentUser(81L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);
    private static final CurrentUser MEMBER = new CurrentUser(82L, "kid@example.com", "아이", 1L, UserRole.MEMBER);

    private static final List<String> WITHOUT_SKILLS = List.of("web", "fos-assistant");

    @Autowired
    SkillPackageService packages;

    @Autowired
    SkillService skills;

    @Autowired
    SkillStore store;

    @Autowired
    PreviousSkillStore previousStore;

    @Autowired
    AgentRepository agents;

    @Autowired
    HermesSkillClient skillClient;

    @Autowired
    HermesToolsetClient toolsets;

    @BeforeEach
    void setUp() {
        for (String profile : List.of(OWNED_PROFILE, GROUP_PROFILE)) {
            store.deleteAll(profile);
        }
        for (String code : List.of(OWNED, GROUP)) {
            agents.findByCode(code).ifPresent(agents::delete);
        }
        agents.save(agent(OWNED, OWNED_PROFILE, AgentVisibility.PRIVATE));
        agents.save(agent(GROUP, GROUP_PROFILE, AgentVisibility.GROUP));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(WITHOUT_SKILLS);
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("hermes-help", "Hermes 기본", true)));
    }

    @Test
    @DisplayName("새 스킬 미리보기는 모든 파일이 ADDED 이고 지문이 없으며 아무것도 쓰지 않는다")
    void previewOfNewSkillIsAllAddedAndWritesNothing() throws IOException {
        SkillPackagePreview preview =
                packages.preview(OWNER, OWNED, zip("SKILL.md", skillMd("weekly-plan"), "references/guide.md", "안내"));

        assertThat(preview.name()).isEqualTo("weekly-plan");
        assertThat(preview.description()).isEqualTo("이번 주 계획을 세운다");
        assertThat(preview.skillMdHead()).isEqualTo(skillMd("weekly-plan"));
        assertThat(preview.existing()).isFalse();
        assertThat(preview.baseDigest()).isNull();
        assertThat(preview.hasScripts()).isFalse();
        assertThat(preview.problems()).isEmpty();
        assertThat(preview.files())
                .containsExactly(
                        new SkillPackageFile("SKILL.md", utf8(skillMd("weekly-plan")), ADDED),
                        new SkillPackageFile("references/guide.md", 6L, ADDED));
        assertThat(store.currentVersion(OWNED_PROFILE)).as("미리보기 뒤 지금 버전").isEmpty();
        verify(skillClient, never()).publish(anyString(), anyList(), any(), anyString());
    }

    @Test
    @DisplayName("새 스킬 묶음을 올리면 저장되고 Hermes 설정 쓰기가 불린다")
    void uploadOfNewSkillSavesAndPublishes() throws IOException {
        SkillDetail saved = packages.upload(
                OWNER, OWNED, zip("SKILL.md", skillMd("weekly-plan"), "references/guide.md", "안내"), null);

        assertThat(saved.name()).isEqualTo("weekly-plan");
        assertThat(saved.previousSavedAt()).isNull();
        assertThat(store.readCurrent(OWNED_PROFILE))
                .containsEntry(
                        "weekly-plan",
                        new SkillBundle(
                                "weekly-plan",
                                skillMd("weekly-plan"),
                                List.of(new SkillFile("references/guide.md", "안내"))));
        verify(skillClient).publish(eq(OWNED_PROFILE), anyList(), any(), eq("u" + OWNER.id()));
    }

    @Test
    @DisplayName("같은 이름의 다른 묶음은 바뀜을 파일마다 주고, 그 지문으로 올리면 덮어쓰며 첫 내용이 이전 버전이 된다")
    void overwriteWithPreviewDigestKeepsFirstContentAsPrevious() throws IOException {
        skills.save(
                OWNER,
                OWNED,
                "weekly-plan",
                skillMd("weekly-plan"),
                List.of(new SkillFileInput("references/a.md", "가"), new SkillFileInput("references/b.md", "나나")));
        SkillBundle first = store.readCurrent(OWNED_PROFILE).get("weekly-plan");
        byte[] zip = zip(
                "SKILL.md", skillMd("weekly-plan"),
                "references/a.md", "가 고침",
                "references/c.md", "다");

        SkillPackagePreview preview = packages.preview(OWNER, OWNED, zip);

        assertThat(preview.existing()).isTrue();
        assertThat(preview.baseDigest()).isEqualTo(first.digest());
        assertThat(preview.files())
                .containsExactly(
                        new SkillPackageFile("SKILL.md", utf8(skillMd("weekly-plan")), SAME),
                        new SkillPackageFile("references/a.md", utf8("가 고침"), CHANGED),
                        new SkillPackageFile("references/c.md", 3L, ADDED),
                        new SkillPackageFile("references/b.md", 6L, REMOVED));

        SkillDetail saved = packages.upload(OWNER, OWNED, zip, preview.baseDigest());

        assertThat(saved.previousSavedAt()).isNotNull();
        assertThat(previousStore.readPrevious(OWNED_PROFILE, "weekly-plan").map(PreviousSkill::bundle))
                .contains(first);
        assertThat(store.readCurrent(OWNED_PROFILE).get("weekly-plan").files())
                .extracting(SkillFile::path)
                .containsExactly("references/a.md", "references/c.md");
    }

    @Test
    @DisplayName("지문 없이 덮어쓰거나, 미리보기 뒤 고친 스킬에 옛 지문으로 올리면 SKILL_CHANGED 이고 지금 버전이 그대로다")
    void staleOrMissingDigestIsSkillChanged() throws IOException {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        byte[] zip = zip("SKILL.md", skillMd("weekly-plan") + "\n묶음");
        String staleDigest = packages.preview(OWNER, OWNED, zip).baseDigest();
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan") + "\n편집기", List.of());
        Optional<String> version = store.currentVersion(OWNED_PROFILE);

        assertCode(() -> packages.upload(OWNER, OWNED, zip, null), ErrorCode.SKILL_CHANGED);
        assertCode(() -> packages.upload(OWNER, OWNED, zip, staleDigest), ErrorCode.SKILL_CHANGED);
        assertCode(
                () -> packages.upload(OWNER, OWNED, zip("SKILL.md", skillMd("shopping")), staleDigest),
                ErrorCode.SKILL_CHANGED);

        assertThat(store.currentVersion(OWNED_PROFILE)).isEqualTo(version);
        assertThat(store.readCurrent(OWNED_PROFILE).get("weekly-plan").skillMd())
                .endsWith("\n편집기");
    }

    @Test
    @DisplayName("terminal 이 꺼진 에이전트에 scripts 묶음은 미리보기에 SCRIPTS_NEED_SANDBOX, 올리기는 SKILL_SCRIPTS_NEED_SANDBOX 다")
    void scriptsWithoutTerminalNeedSandbox() throws IOException {
        byte[] zip = zip("SKILL.md", skillMd("weekly-plan"), "scripts/run.sh", "echo hi");

        SkillPackagePreview preview = packages.preview(OWNER, OWNED, zip);

        assertThat(preview.hasScripts()).isTrue();
        assertThat(preview.problems())
                .containsExactly(new SkillPackageProblem(SkillPackageReason.SCRIPTS_NEED_SANDBOX, null));
        assertCode(() -> packages.upload(OWNER, OWNED, zip, null), ErrorCode.SKILL_SCRIPTS_NEED_SANDBOX);
        assertThat(store.currentVersion(OWNED_PROFILE)).isEmpty();
    }

    @Test
    @DisplayName("새 스킬의 설명은 60자까지 받고 61자면 DESCRIPTION_TOO_LONG, Hermes 기본 스킬과 같은 이름은 NAME_TAKEN 이다")
    void newSkillRulesShowAsProblems() throws IOException {
        assertThat(packages.preview(OWNER, OWNED, zip("SKILL.md", skillMd("sixty", "가".repeat(60))))
                        .problems())
                .isEmpty();
        assertThat(packages.preview(OWNER, OWNED, zip("SKILL.md", skillMd("sixty-one", "가".repeat(61))))
                        .problems())
                .containsExactly(new SkillPackageProblem(SkillPackageReason.DESCRIPTION_TOO_LONG, "SKILL.md"));
        assertThat(packages.preview(OWNER, OWNED, zip("SKILL.md", skillMd("hermes-help")))
                        .problems())
                .containsExactly(new SkillPackageProblem(SkillPackageReason.NAME_TAKEN, null));
    }

    @Test
    @DisplayName("편집자가 아니면 zip 을 보기 전에 미리보기와 올리기, 큰 zip 경로 모두 FORBIDDEN 이다")
    void nonEditorIsForbiddenBeforeReadingZip() {
        byte[] notZip = "not a zip".getBytes(StandardCharsets.UTF_8);

        assertCode(() -> packages.preview(MEMBER, GROUP, notZip), ErrorCode.FORBIDDEN);
        assertCode(() -> packages.upload(MEMBER, GROUP, notZip, null), ErrorCode.FORBIDDEN);
        assertCode(() -> packages.previewTooLarge(MEMBER, GROUP), ErrorCode.FORBIDDEN);
        assertCode(() -> packages.uploadTooLarge(MEMBER, GROUP), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("문제가 있는 묶음을 올리면 SKILL_PACKAGE_INVALID 이고 메시지에 첫 문제가 있으며 지금 버전이 그대로다")
    void invalidPackageUploadNamesFirstProblem() throws IOException {
        String secret = "token: " + "sk-" + "a".repeat(24);
        byte[] withSecret = zip("SKILL.md", skillMd("weekly-plan"), "references/a.md", secret);
        byte[] withoutSkillMd = zip("references/a.md", "안내");

        assertMessage(
                () -> packages.upload(OWNER, OWNED, withSecret, null),
                ErrorCode.SKILL_PACKAGE_INVALID,
                "SECRET_VALUE: references/a.md");
        assertMessage(
                () -> packages.upload(OWNER, OWNED, withoutSkillMd, null),
                ErrorCode.SKILL_PACKAGE_INVALID,
                "NO_SKILL_MD");
        assertThat(store.currentVersion(OWNED_PROFILE)).isEmpty();
    }

    @Test
    @DisplayName("올린 스킬이 한도에 닿으면 새 스킬 미리보기는 LIMIT_REACHED 이고 이미 있는 스킬은 문제가 없다")
    void limitReachedOnlyForNewSkill() throws IOException {
        Map<String, SkillBundle> bundles = new LinkedHashMap<>();
        for (int i = 1; i <= 30; i++) {
            String name = String.format("skill-%02d", i);
            bundles.put(name, new SkillBundle(name, skillMd(name), List.of()));
        }
        store.markPublished(OWNED_PROFILE, store.writeVersion(OWNED_PROFILE, bundles));

        assertThat(packages.preview(OWNER, OWNED, zip("SKILL.md", skillMd("weekly-plan")))
                        .problems())
                .containsExactly(new SkillPackageProblem(SkillPackageReason.LIMIT_REACHED, null));
        assertThat(packages.preview(OWNER, OWNED, zip("SKILL.md", skillMd("skill-01") + "\n고침"))
                        .problems())
                .isEmpty();
    }

    @Test
    @DisplayName("크기 상한을 넘은 zip 은 편집자에게 미리보기의 ZIP_TOO_LARGE 와 올리기의 SKILL_PACKAGE_INVALID 다")
    void tooLargeZipForEditor() {
        assertThat(packages.previewTooLarge(OWNER, OWNED).problems())
                .containsExactly(new SkillPackageProblem(SkillPackageReason.ZIP_TOO_LARGE, null));
        assertMessage(() -> packages.uploadTooLarge(OWNER, OWNED), ErrorCode.SKILL_PACKAGE_INVALID, "ZIP_TOO_LARGE");
    }

    @Test
    @DisplayName("지문이 빈 문자열이거나 공백뿐인 새 스킬 올리기는 지문이 없는 것으로 보고 저장한다")
    void blankDigestForNewSkillSaves() throws IOException {
        packages.upload(OWNER, OWNED, zip("SKILL.md", skillMd("weekly-plan")), "");
        packages.upload(OWNER, OWNED, zip("SKILL.md", skillMd("shopping")), "  ");

        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("weekly-plan", "shopping");
    }

    /** 경로와 내용을 번갈아 받아 그 차례대로 항목을 쓴 zip 이다. */
    private static byte[] zip(String... pathsAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bytes)) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                out.putArchiveEntry(new ZipArchiveEntry(pathsAndContents[i]));
                out.write(pathsAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static long utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String skillMd(String name) {
        return skillMd(name, "이번 주 계획을 세운다");
    }

    private static String skillMd(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + name + "\n";
    }

    private static Agent agent(String code, String profile, AgentVisibility visibility) {
        return Agent.of(
                code,
                code,
                profile,
                "http://agent-runtime.test/p/" + profile,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                OWNER.id(),
                Instant.now());
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }

    private static void assertMessage(ThrowingCallable call, ErrorCode expected, String message) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .hasMessage(message)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }
}
