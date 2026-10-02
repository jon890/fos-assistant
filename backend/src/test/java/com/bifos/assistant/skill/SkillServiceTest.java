package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesRequestRejected;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillBundle;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import com.bifos.assistant.skill.application.SkillDetail;
import com.bifos.assistant.skill.application.SkillFile;
import com.bifos.assistant.skill.application.SkillFileInfo;
import com.bifos.assistant.skill.application.SkillFileInput;
import com.bifos.assistant.skill.application.SkillList;
import com.bifos.assistant.skill.application.SkillListItem;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.application.SkillSource;
import com.bifos.assistant.skill.application.SkillUsageSummary;
import com.bifos.assistant.skill.application.SkillsChanged;
import com.bifos.assistant.skill.infra.SkillStore;
import com.bifos.assistant.user.domain.type.UserRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 스킬을 저장하고 지우는 순서와 게시 실패의 처리, 누가 할 수 있는지를 본다.
 *
 * <p>테스트 클래스에 트랜잭션을 두지 않는다. 서비스의 트랜잭션과 에이전트 행 잠금이 실제로 돌아야 동시
 * 저장이 차례로 도는지 볼 수 있다. 대시보드의 스킬 경로와 도구 목록만 대역이고, 버전 디렉터리는
 * {@code application-test.yml} 의 경로에 실제로 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class SkillServiceTest {

    private static final String OWNED = "skill-owned";
    private static final String OWNED_PROFILE = "skill-owned-profile";
    private static final String GROUP = "skill-group";
    private static final String GROUP_PROFILE = "skill-group-profile";

    private static final CurrentUser OWNER = new CurrentUser(81L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);
    private static final CurrentUser MEMBER = new CurrentUser(82L, "kid@example.com", "아이", 1L, UserRole.MEMBER);

    /** {@code application-test.yml} 의 스킬 루트다. 두 루트가 같아 게시된 경로를 그대로 열 수 있다. */
    private static final Path SKILL_ROOT = Path.of("build/test-skills");

    private static final List<String> WITHOUT_SKILLS = List.of("web", "fos-assistant");
    private static final List<String> WITH_SKILLS = List.of("web", "skills", "fos-assistant");

    @Autowired
    SkillService skills;

    @Autowired
    SkillStore store;

    @Autowired
    AgentRepository agents;

    @MockitoBean
    HermesSkillClient skillClient;

    @MockitoBean
    HermesToolsetClient toolsets;

    /** 스킬 커맨드의 캐시를 비우는 {@link SkillsChanged} 를 서비스가 냈는지 본다. */
    @Autowired
    ApplicationEvents applicationEvents;

    @Autowired
    SkillCommandCatalog commandCatalog;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        for (String profile : List.of(OWNED_PROFILE, GROUP_PROFILE)) {
            store.deleteAll(profile);
        }
        for (String code : List.of(OWNED, GROUP)) {
            agents.findByCode(code).ifPresent(agents::delete);
        }
        agents.save(agent(OWNED, OWNED_PROFILE, AgentVisibility.PRIVATE, OWNER.id()));
        agents.save(agent(GROUP, GROUP_PROFILE, AgentVisibility.GROUP, OWNER.id()));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(WITHOUT_SKILLS);
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("hermes-help", "Hermes 기본", true)));
    }

    @Test
    @DisplayName("저장하면 한 번의 게시에 새 경로와 skills 가 든 도구 목록이 함께 간다")
    void saveSendsNewPathAndToolListWithSkillsInOnePublish() throws Exception {
        SkillDetail saved = skills.save(
                OWNER,
                OWNED,
                "weekly-plan",
                skillMd("weekly-plan"),
                List.of(new SkillFileInput("references/guide.md", "안내")));

        String version = store.currentVersion(OWNED_PROFILE).orElseThrow();
        ArgumentCaptor<List<String>> dirs = captor();
        ArgumentCaptor<List<String>> apiServer = captor();
        verify(skillClient).publish(eq(OWNED_PROFILE), dirs.capture(), apiServer.capture());
        assertThat(dirs.getValue()).containsExactly(store.agentPath(OWNED_PROFILE, version));
        assertThat(apiServer.getValue()).containsExactly("web", "skills", "fos-assistant");
        assertThat(saved)
                .isEqualTo(new SkillDetail(
                        "weekly-plan",
                        "이번 주 계획을 세운다",
                        skillMd("weekly-plan"),
                        List.of(new SkillFileInfo("references/guide.md", 6L))));
        assertThat(Files.readAllLines(Path.of(dirs.getValue().get(0), "weekly-plan", "SKILL.md")))
                .as("게시한 경로에 SKILL.md 가 있다")
                .contains("name: weekly-plan");
    }

    @Test
    @DisplayName("skills 가 이미 켜져 있으면 도구 목록은 null 이다")
    void toolListIsNullWhenSkillsAlreadyEnabled() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(WITH_SKILLS);

        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());

        verify(skillClient).publish(eq(OWNED_PROFILE), anyList(), isNull());
        verify(toolsets, never()).writeApiServer(anyString(), anyList());
    }

    @Test
    @DisplayName("게시가 4xx 로 거절되면 새 디렉터리가 없고 지금 버전이 그대로다")
    void leavesNoNewDirectoryAndKeepsCurrentVersionWhenPublishGets4xx() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        String before = store.currentVersion(OWNED_PROFILE).orElseThrow();
        doThrow(new HermesRequestRejected(
                        ErrorCode.HERMES_UNAVAILABLE, "rejected", new HttpClientErrorException(HttpStatus.BAD_REQUEST)))
                .when(skillClient)
                .publish(anyString(), anyList(), any());

        assertCode(
                () -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);

        assertThat(store.currentVersion(OWNED_PROFILE)).contains(before);
        assertThat(versionDirs(OWNED_PROFILE)).as("거절된 버전 디렉터리는 지운다").containsExactly(before);
        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("weekly-plan");
    }

    @Test
    @DisplayName("게시가 timeout 이나 5xx 로 실패하면 표식 없이 남고 다음 성공이 그것을 지우며 실패한 변경은 없다")
    void leavesUnmarkedOnTimeoutOr5xxAndNextSuccessClearsIt() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        String first = store.currentVersion(OWNED_PROFILE).orElseThrow();
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "read timed out"))
                .when(skillClient)
                .publish(anyString(), anyList(), any());

        assertCode(
                () -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);

        assertThat(store.currentVersion(OWNED_PROFILE)).as("지금 버전은 옛 것이다").contains(first);
        assertThat(versionDirs(OWNED_PROFILE)).as("실패한 버전은 표식 없이 남는다").hasSize(2);

        doAnswer(call -> null).when(skillClient).publish(anyString(), anyList(), any());
        skills.save(OWNER, OWNED, "cooking", skillMd("cooking"), List.of());

        String third = store.currentVersion(OWNED_PROFILE).orElseThrow();
        assertThat(versionDirs(OWNED_PROFILE)).as("표식 없는 디렉터리는 다음 성공이 지운다").containsExactlyInAnyOrder(first, third);
        assertThat(store.readCurrent(OWNED_PROFILE))
                .as("실패한 저장의 변경은 반영되지 않는다")
                .containsOnlyKeys("cooking", "weekly-plan");
    }

    @Test
    @DisplayName("timeout 뒤 Hermes 에 뜬 새 스킬은 올린 스킬로 보이고 같은 이름으로 다시 저장된다")
    void skillAppearingInHermesAfterTimeoutLooksUploadedAndSavesAgainByName() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "read timed out"))
                .when(skillClient)
                .publish(anyString(), anyList(), any());
        assertCode(
                () -> skills.save(
                        OWNER,
                        OWNED,
                        "shopping",
                        skillMd("shopping"),
                        List.of(new SkillFileInput("references/list.md", "장보기 목록"))),
                ErrorCode.HERMES_UNAVAILABLE);
        // Hermes 는 응답만 잃고 그 버전을 반영해 목록에 새 이름을 보인다.
        when(skillClient.list(OWNED_PROFILE))
                .thenReturn(List.of(
                        new HermesSkill("hermes-help", "Hermes 기본", true),
                        new HermesSkill("shopping", "shopping 을 한다", true),
                        new HermesSkill("weekly-plan", "이번 주 계획을 세운다", true)));

        assertThat(skills.list(OWNER, OWNED).skills())
                .as("Hermes 목록의 새 이름은 올린 스킬이다")
                .contains(new SkillListItem(
                        "shopping", "shopping 을 한다", SkillSource.UPLOADED, true, new SkillUsageSummary(0, null)));
        assertThat(skills.read(OWNER, OWNED, "shopping").files())
                .as("편집 화면이 표식 없는 버전의 원문을 연다")
                .containsExactly(new SkillFileInfo("references/list.md", 16L));

        doAnswer(call -> null).when(skillClient).publish(anyString(), anyList(), any());
        skills.save(
                OWNER, OWNED, "shopping", skillMd("shopping"), List.of(new SkillFileInput("references/list.md", null)));

        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("shopping", "weekly-plan");
        assertThat(store.readCurrent(OWNED_PROFILE).get("shopping").files())
                .as("본문을 생략한 파일은 표식 없는 버전의 내용을 쓴다")
                .containsExactly(new SkillFile("references/list.md", "장보기 목록"));
        assertThat(store.readPending(OWNED_PROFILE)).isEmpty();
    }

    @Test
    @DisplayName("timeout 뒤 Hermes 에만 뜬 스킬을 지우면 지금 버전을 다시 게시하고 지금 버전이 없으면 빈 목록을 게시한다")
    void deletingSkillOnlyInHermesAfterTimeoutRepublishesCurrentVersion() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "read timed out"))
                .when(skillClient)
                .publish(anyString(), anyList(), any());
        assertCode(
                () -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);
        assertCode(
                () -> skills.save(OWNER, GROUP, "cooking", skillMd("cooking"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);
        doAnswer(call -> null).when(skillClient).publish(anyString(), anyList(), any());

        skills.delete(OWNER, OWNED, "shopping");

        String republished = store.currentVersion(OWNED_PROFILE).orElseThrow();
        verify(skillClient).publish(eq(OWNED_PROFILE), eq(List.of(store.agentPath(OWNED_PROFILE, republished))), any());
        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("weekly-plan");
        assertThat(store.readPending(OWNED_PROFILE))
                .as("Hermes 가 벗어난 표식 없는 버전은 지운다")
                .isEmpty();
        assertCode(() -> skills.delete(OWNER, OWNED, "shopping"), ErrorCode.SKILL_NOT_FOUND);

        skills.delete(OWNER, GROUP, "cooking");

        verify(skillClient).publish(GROUP_PROFILE, List.of(), null);
        assertThat(SKILL_ROOT.resolve(GROUP_PROFILE)).doesNotExist();
    }

    @Test
    @DisplayName("저장과 켜고 끄기와 지우기가 Hermes 에 반영되면 그 에이전트의 SkillsChanged 를 낸다")
    void emitsSkillsChangedOfAgentWhenSaveToggleAndDeleteReachHermes() {
        Long agentId = agents.findByCode(OWNED).orElseThrow().id();

        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of());
        skills.toggle(OWNER, OWNED, "weekly-plan", false);
        skills.delete(OWNER, OWNED, "weekly-plan");
        skills.delete(OWNER, OWNED, "shopping");

        assertThat(applicationEvents.stream(SkillsChanged.class))
                .as("저장 둘, 켜고 끄기 하나, 지우기 둘(마지막 스킬 포함)")
                .containsExactly(
                        new SkillsChanged(agentId),
                        new SkillsChanged(agentId),
                        new SkillsChanged(agentId),
                        new SkillsChanged(agentId),
                        new SkillsChanged(agentId));
    }

    /**
     * 사건을 받는 쪽은 트랜잭션이 끝난 뒤에 받는다. 저장과 지우기를 바깥 트랜잭션에 넣어 끝나기 전에는 캐시가
     * 그대로인 것을 본다. 커밋하면 비워지고, 되돌려도 비워진다. 되돌린 지우기도 Hermes 게시와 디렉터리 삭제는
     * 이미 끝났기 때문이다. 트랜잭션 없는 켜고 끄기는 바로 비운다.
     */
    @Test
    @DisplayName("커맨드 이름 캐시는 저장과 지우기의 트랜잭션이 커밋이든 되돌림이든 끝난 뒤에 비워지고 켜고 끄기는 바로 비워진다")
    void clearsCommandNameCacheAfterSaveAndDeleteTxEndsAndOnToggle() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(WITH_SKILLS);
        Agent agent = agents.findByCode(OWNED).orElseThrow();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        assertThat(commandCatalog.enabledNames(agent)).as("처음 읽은 것").containsExactly("hermes-help");

        outer.executeWithoutResult(status -> {
            skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
            assertThat(commandCatalog.enabledNames(agent)).as("저장의 커밋 전").containsExactly("hermes-help");
        });
        assertThat(commandCatalog.enabledNames(agent))
                .as("저장이 커밋된 뒤")
                .containsExactlyInAnyOrder("hermes-help", "weekly-plan");

        outer.executeWithoutResult(status -> {
            skills.delete(OWNER, OWNED, "weekly-plan");
            assertThat(commandCatalog.enabledNames(agent))
                    .as("지우기의 트랜잭션이 끝나기 전")
                    .containsExactlyInAnyOrder("hermes-help", "weekly-plan");
            status.setRollbackOnly();
        });
        // 캐시가 비워져 다시 읽었으면 지운 스킬은 없고 Hermes 목록이 준 이름만 남는다.
        assertThat(commandCatalog.enabledNames(agent)).as("지우기를 되돌린 뒤").containsExactly("hermes-help");

        when(skillClient.list(OWNED_PROFILE)).thenReturn(List.of(new HermesSkill("hermes-help", "Hermes 기본", false)));
        skills.toggle(OWNER, OWNED, "hermes-help", false);
        assertThat(commandCatalog.enabledNames(agent)).as("끈 뒤").isEmpty();
    }

    @Test
    @DisplayName("Hermes 가 거절해 저장과 켜고 끄기와 지우기가 실패하면 SkillsChanged 를 내지 않는다")
    void emitsNoSkillsChangedWhenHermesRejectsSaveToggleOrDelete() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        applicationEvents.clear();
        HermesRequestRejected rejected = new HermesRequestRejected(
                ErrorCode.HERMES_UNAVAILABLE, "rejected", new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        doThrow(rejected).when(skillClient).publish(anyString(), anyList(), any());
        doThrow(rejected).when(skillClient).toggle(anyString(), anyString(), anyBoolean());

        assertCode(
                () -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);
        assertCode(() -> skills.toggle(OWNER, OWNED, "weekly-plan", false), ErrorCode.HERMES_UNAVAILABLE);
        assertCode(() -> skills.delete(OWNER, OWNED, "weekly-plan"), ErrorCode.HERMES_UNAVAILABLE);

        assertThat(applicationEvents.stream(SkillsChanged.class)).isEmpty();
    }

    @Test
    @DisplayName("편집자가 아니면 FORBIDDEN 이고 아무것도 쓰지 않는다")
    void nonEditorIsForbiddenAndWritesNothing() {
        assertCode(
                () -> skills.save(MEMBER, GROUP, "weekly-plan", skillMd("weekly-plan"), List.of()),
                ErrorCode.FORBIDDEN);
        assertCode(() -> skills.read(MEMBER, GROUP, "weekly-plan"), ErrorCode.FORBIDDEN);
        assertCode(() -> skills.delete(MEMBER, GROUP, "weekly-plan"), ErrorCode.FORBIDDEN);
        assertCode(() -> skills.toggle(MEMBER, GROUP, "hermes-help", false), ErrorCode.FORBIDDEN);
        assertCode(
                () -> skills.save(MEMBER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of()),
                ErrorCode.AGENT_NOT_FOUND);

        verify(skillClient, never()).publish(anyString(), anyList(), any());
        assertThat(store.currentVersion(GROUP_PROFILE)).isEmpty();
    }

    @Test
    @DisplayName("입력 규칙을 어기면 VALIDATION FAILED 이고 Hermes 이름과 같으면 SKILL NAME TAKEN 이다")
    void breakingInputRulesIsValidationFailedAndHermesNameIsSkillNameTaken() {
        assertCode(
                () -> skills.save(OWNER, OWNED, "weekly-plan", skillMd("other-name"), List.of()),
                ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> skills.save(
                        OWNER,
                        OWNED,
                        "weekly-plan",
                        skillMd("weekly-plan"),
                        List.of(new SkillFileInput("scripts/run.sh", "echo"))),
                ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> skills.save(
                        OWNER,
                        OWNED,
                        "weekly-plan",
                        skillMd("weekly-plan"),
                        IntStream.rangeClosed(1, 21)
                                .mapToObj(i -> new SkillFileInput("references/f" + i + ".md", "x"))
                                .toList()),
                ErrorCode.VALIDATION_FAILED);
        String hundredThousand = "a".repeat(100_000);
        assertCode(
                () -> skills.save(
                        OWNER,
                        OWNED,
                        "weekly-plan",
                        skillMd("weekly-plan"),
                        IntStream.rangeClosed(1, 11)
                                .mapToObj(i -> new SkillFileInput("references/f" + i + ".md", hundredThousand))
                                .toList()),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> skills.save(OWNER, OWNED, "new", skillMd("new"), List.of()), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> skills.save(OWNER, OWNED, "weekly-plan", "# 앞머리가 없다", List.of()), ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> skills.save(OWNER, OWNED, "hermes-help", skillMd("hermes-help"), List.of()),
                ErrorCode.SKILL_NAME_TAKEN);

        verify(skillClient, never()).publish(anyString(), anyList(), any());
        assertThat(store.currentVersion(OWNED_PROFILE)).isEmpty();
    }

    @Test
    @DisplayName("정확히 20개 파일과 정확히 1 MiB 는 받고 1 바이트가 넘으면 거절한다")
    void accepts20FilesAndExactly1MiBAndRejectsOverByOneByte() {
        String skillMd = skillMd("weekly-plan");
        long fileBytes = SkillService.MAX_TOTAL_BYTES - skillMd.getBytes(StandardCharsets.UTF_8).length;

        SkillDetail saved = skills.save(OWNER, OWNED, "weekly-plan", skillMd, asciiFilesTotaling(fileBytes, 20));

        assertThat(saved.files()).hasSize(20);
        assertThat(saved.files().stream().mapToLong(SkillFileInfo::size).sum()
                        + skillMd.getBytes(StandardCharsets.UTF_8).length)
                .isEqualTo(SkillService.MAX_TOTAL_BYTES);
        assertCode(
                () -> skills.save(OWNER, OWNED, "weekly-plan", skillMd, asciiFilesTotaling(fileBytes + 1, 20)),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("본문을 생략한 파일은 지금 내용이 그대로 새 버전에 있고 없는 경로면 거절한다")
    void omittedBodyFileKeepsCurrentContentAndMissingPathIsRejected() {
        skills.save(
                OWNER,
                OWNED,
                "weekly-plan",
                skillMd("weekly-plan"),
                List.of(new SkillFileInput("references/guide.md", "처음 안내")));

        skills.save(
                OWNER,
                OWNED,
                "weekly-plan",
                skillMd("weekly-plan") + "\n고침",
                List.of(
                        new SkillFileInput("references/guide.md", null),
                        new SkillFileInput("templates/note.md", "새 파일")));

        SkillBundle current = store.readCurrent(OWNED_PROFILE).get("weekly-plan");
        assertThat(current.skillMd()).endsWith("고침");
        assertThat(current.files())
                .containsExactly(
                        new SkillFile("references/guide.md", "처음 안내"), new SkillFile("templates/note.md", "새 파일"));
        assertCode(
                () -> skills.save(
                        OWNER,
                        OWNED,
                        "weekly-plan",
                        skillMd("weekly-plan"),
                        List.of(new SkillFileInput("references/missing.md", null))),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("읽기는 앞머리를 포함한 원문과 UTF 8 크기를 주고 목록은 출처를 붙인다")
    void readGivesOriginalWithFrontmatterAndUtf8SizeAndListAddsSource() {
        skills.save(
                OWNER,
                OWNED,
                "weekly-plan",
                skillMd("weekly-plan"),
                List.of(new SkillFileInput("references/guide.md", "안내")));
        when(skillClient.list(OWNED_PROFILE))
                .thenReturn(List.of(
                        new HermesSkill("hermes-help", "Hermes 기본", true),
                        new HermesSkill("weekly-plan", "이번 주 계획을 세운다", false)));

        SkillDetail detail = skills.read(OWNER, OWNED, "weekly-plan");
        assertThat(detail.body()).startsWith("---\nname: weekly-plan");
        assertThat(detail.description()).isEqualTo("이번 주 계획을 세운다");
        assertThat(detail.files()).containsExactly(new SkillFileInfo("references/guide.md", 6L));
        assertCode(() -> skills.read(OWNER, OWNED, "hermes-help"), ErrorCode.SKILL_NOT_FOUND);

        SkillList list = skills.list(OWNER, OWNED);
        assertThat(list.editable()).isTrue();
        assertThat(list.uploadLimit()).as("설정하지 않은 기본 한도").isEqualTo(30);
        assertThat(list.skillsToolsetEnabled()).as("대역의 켜진 목록에 skills 가 없다").isFalse();
        // 편집자에게는 호출이 없는 스킬에도 합계가 0 으로 붙는다.
        SkillUsageSummary noUse = new SkillUsageSummary(0, null);
        assertThat(list.skills())
                .containsExactly(
                        new SkillListItem("hermes-help", "Hermes 기본", SkillSource.HERMES, true, noUse),
                        new SkillListItem("weekly-plan", "이번 주 계획을 세운다", SkillSource.UPLOADED, false, noUse));

        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(WITH_SKILLS);
        when(skillClient.list(GROUP_PROFILE)).thenReturn(List.of());
        SkillList readerList = skills.list(MEMBER, GROUP);
        assertThat(readerList.editable()).isFalse();
        assertThat(readerList.skillsToolsetEnabled()).isTrue();
        assertThat(readerList.skills()).isEmpty();
    }

    @Test
    @DisplayName("지우면 그 스킬이 빠진 버전이 게시되고 마지막 스킬을 지우면 빈 목록이 게시된다")
    void deleteRepublishesWithoutSkillAndLastDeleteRepublishesEmptyList() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of());

        skills.delete(OWNER, OWNED, "weekly-plan");

        String afterFirst = store.currentVersion(OWNED_PROFILE).orElseThrow();
        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("shopping");
        verify(skillClient).publish(eq(OWNED_PROFILE), eq(List.of(store.agentPath(OWNED_PROFILE, afterFirst))), any());
        assertCode(() -> skills.delete(OWNER, OWNED, "weekly-plan"), ErrorCode.SKILL_NOT_FOUND);

        skills.delete(OWNER, OWNED, "shopping");

        verify(skillClient).publish(OWNED_PROFILE, List.of(), null);
        assertThat(store.currentVersion(OWNED_PROFILE)).isEmpty();
        assertThat(SKILL_ROOT.resolve(OWNED_PROFILE)).doesNotExist();
    }

    @Test
    @DisplayName("켜고 끄기는 Hermes 이름 규칙으로 보고 대시보드에 그대로 넘긴다")
    void toggleChecksHermesNameRuleAndPassesAsIsToDashboard() {
        skills.toggle(OWNER, OWNED, "hermes-help", false);
        skills.toggle(OWNER, OWNED, "note_taking.v2", true);

        verify(skillClient).toggle(OWNED_PROFILE, "hermes-help", false);
        verify(skillClient).toggle(OWNED_PROFILE, "note_taking.v2", true);
        for (String bad : List.of("..", ".hidden", "Upper", "a/b", "a".repeat(65), "")) {
            assertCode(() -> skills.toggle(OWNER, OWNED, bad, false), ErrorCode.VALIDATION_FAILED);
        }
        verify(skillClient, never()).toggle(eq(OWNED_PROFILE), eq(".."), anyBoolean());
    }

    /**
     * 같은 에이전트에 두 저장이 동시에 와도 둘 다 반영된 버전이 남는다.
     *
     * <p>대역이 첫 게시를 붙잡는 동안 둘째가 잠금을 기다린다. 잠금이 없으면 둘 다 빈 지금 버전을 읽어
     * 뒤의 것이 앞의 것을 덮는다.
     */
    @Test
    @DisplayName("같은 에이전트에 두 저장이 동시에 와도 둘 다 반영된 버전이 남는다")
    void keepsVersionWithBothSavesWhenTwoSavesArriveTogetherForSameAgent() throws Exception {
        CountDownLatch firstPublishStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstPublish = new CountDownLatch(1);
        doAnswer(call -> {
                    if (firstPublishStarted.getCount() > 0) {
                        firstPublishStarted.countDown();
                        assertThat(releaseFirstPublish.await(10, TimeUnit.SECONDS))
                                .as("첫 게시를 풀어 주기까지")
                                .isTrue();
                    }
                    return null;
                })
                .when(skillClient)
                .publish(anyString(), anyList(), any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SkillDetail> first =
                    pool.submit(() -> skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of()));
            assertThat(firstPublishStarted.await(10, TimeUnit.SECONDS))
                    .as("첫 저장이 게시에 닿기까지")
                    .isTrue();
            Future<SkillDetail> second =
                    pool.submit(() -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()));
            // 둘째가 잠금에 걸려 있는 동안에는 게시가 한 번뿐이다.
            Thread.sleep(300);
            verify(skillClient, Mockito.times(1)).publish(anyString(), anyList(), any());
            assertThat(second.isDone()).as("둘째 저장은 첫째가 끝날 때까지 기다린다").isFalse();

            releaseFirstPublish.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("shopping", "weekly-plan");
        verify(skillClient, Mockito.times(2)).publish(eq(OWNED_PROFILE), anyList(), any());
    }

    @Test
    @DisplayName("새 스킬의 설명은 60자까지 저장되고 61자는 VALIDATION FAILED 이며 게시하지 않는다")
    void newSkillDescriptionSavesUpTo60CharsAnd61IsValidationFailed() {
        assertCode(
                () -> skills.save(OWNER, OWNED, "too-long", skillMd("too-long", "가".repeat(61)), List.of()),
                ErrorCode.VALIDATION_FAILED);

        verify(skillClient, never()).publish(anyString(), anyList(), any());
        assertThat(store.currentVersion(OWNED_PROFILE)).isEmpty();

        skills.save(OWNER, OWNED, "just-fits", skillMd("just-fits", "가".repeat(60)), List.of());

        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("just-fits");
    }

    @Test
    @DisplayName("이미 올린 스킬은 61자 설명으로 고쳐도 저장된다")
    void alreadyUploadedSkillSavesEditToDescriptionOf61Chars() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan", "가".repeat(60)), List.of());

        SkillDetail saved = skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan", "나".repeat(61)), List.of());

        assertThat(saved.description()).isEqualTo("나".repeat(61));
        assertThat(store.readCurrent(OWNED_PROFILE).get("weekly-plan").skillMd())
                .contains("나".repeat(61));
    }

    @Test
    @DisplayName("설명 1024자는 고칠 때 받고 1025자는 이미 올린 스킬을 고칠 때도 VALIDATION FAILED 다")
    void description1024AcceptedOnEditAnd1025IsValidationFailed() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());

        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan", "가".repeat(1024)), List.of());
        assertCode(
                () -> skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan", "가".repeat(1025)), List.of()),
                ErrorCode.VALIDATION_FAILED);

        assertThat(store.readCurrent(OWNED_PROFILE).get("weekly-plan").skillMd())
                .as("거절된 저장은 반영되지 않는다")
                .isEqualTo(skillMd("weekly-plan", "가".repeat(1024)));
    }

    @Test
    @DisplayName("앞머리만 있는 원문은 새 스킬이든 이미 올린 스킬을 고치는 것이든 VALIDATION FAILED 다")
    void frontmatterOnlyOriginalIsValidationFailedForNewAndEditedSkills() {
        assertCode(
                () -> skills.save(OWNER, OWNED, "weekly-plan", frontmatterOnly("weekly-plan"), List.of()),
                ErrorCode.VALIDATION_FAILED);
        verify(skillClient, never()).publish(anyString(), anyList(), any());

        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        assertCode(
                () -> skills.save(OWNER, OWNED, "weekly-plan", frontmatterOnly("weekly-plan") + "  \n\n", List.of()),
                ErrorCode.VALIDATION_FAILED);

        assertThat(store.readCurrent(OWNED_PROFILE).get("weekly-plan").skillMd())
                .isEqualTo(skillMd("weekly-plan"));
    }

    @Test
    @DisplayName("올린 스킬이 29개면 30번째 새 스킬은 저장되고 30개면 31번째 새 스킬은 VALIDATION FAILED 이며 게시하지 않는다")
    void thirtiethNewSkillSavesAt29UploadedAndThirtyFirstFailsAt30() {
        publishUploaded(29);

        skills.save(OWNER, OWNED, "skill-30", skillMd("skill-30"), List.of());
        assertThat(store.readCurrent(OWNED_PROFILE)).hasSize(30);
        clearInvocations(skillClient);

        assertCode(
                () -> skills.save(OWNER, OWNED, "skill-31", skillMd("skill-31"), List.of()),
                ErrorCode.VALIDATION_FAILED);

        verify(skillClient, never()).publish(anyString(), anyList(), any());
        assertThat(store.readCurrent(OWNED_PROFILE)).hasSize(30).doesNotContainKey("skill-31");
    }

    @Test
    @DisplayName("올린 스킬이 30개여도 이미 올린 스킬은 고칠 수 있고 하나를 지우면 새 스킬을 받는다")
    void canEditUploadedSkillAt30AndDeletingOneAcceptsNewSkill() {
        publishUploaded(30);

        skills.save(OWNER, OWNED, "skill-01", skillMd("skill-01") + "\n고침", List.of());
        assertThat(store.readCurrent(OWNED_PROFILE).get("skill-01").skillMd()).endsWith("고침");

        skills.delete(OWNER, OWNED, "skill-01");
        skills.save(OWNER, OWNED, "replacement", skillMd("replacement"), List.of());

        assertThat(store.readCurrent(OWNED_PROFILE)).hasSize(30).containsKey("replacement");
    }

    @Test
    @DisplayName("표식 없이 남은 더 새 버전의 이름도 올린 스킬 수에 센다")
    void countsNameOfNewerUnmarkedVersionInUploadedSkills() {
        publishUploaded(29);
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "read timed out"))
                .when(skillClient)
                .publish(anyString(), anyList(), any());
        assertCode(
                () -> skills.save(OWNER, OWNED, "skill-30", skillMd("skill-30"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);
        doAnswer(call -> null).when(skillClient).publish(anyString(), anyList(), any());

        assertThat(store.readCurrent(OWNED_PROFILE)).as("지금 버전은 29개다").hasSize(29);
        assertCode(
                () -> skills.save(OWNER, OWNED, "skill-31", skillMd("skill-31"), List.of()),
                ErrorCode.VALIDATION_FAILED);
        skills.save(OWNER, OWNED, "skill-30", skillMd("skill-30"), List.of());

        assertThat(store.readCurrent(OWNED_PROFILE)).as("표식 없는 버전의 이름은 고칠 수 있다").hasSize(30);
    }

    /**
     * 올린 스킬이 29개일 때 서로 다른 새 스킬 둘이 동시에 와도 하나만 저장된다.
     *
     * <p>대역이 첫 게시를 붙잡는 동안 둘째가 잠금을 기다린다. 개수를 잠금 밖에서 세면 둘 다 29개를 보고
     * 통과해 31개가 된다.
     */
    @Test
    @DisplayName("올린 스킬이 29개일 때 새 스킬 둘이 동시에 와도 하나만 저장된다")
    void savesOnlyOneWhenTwoNewSkillsArriveTogetherAt29Uploaded() throws Exception {
        publishUploaded(29);
        CountDownLatch firstPublishStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstPublish = new CountDownLatch(1);
        doAnswer(call -> {
                    if (firstPublishStarted.getCount() > 0) {
                        firstPublishStarted.countDown();
                        assertThat(releaseFirstPublish.await(10, TimeUnit.SECONDS))
                                .as("첫 게시를 풀어 주기까지")
                                .isTrue();
                    }
                    return null;
                })
                .when(skillClient)
                .publish(anyString(), anyList(), any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SkillDetail> first =
                    pool.submit(() -> skills.save(OWNER, OWNED, "first-new", skillMd("first-new"), List.of()));
            assertThat(firstPublishStarted.await(10, TimeUnit.SECONDS))
                    .as("첫 저장이 게시에 닿기까지")
                    .isTrue();
            Future<SkillDetail> second =
                    pool.submit(() -> skills.save(OWNER, OWNED, "second-new", skillMd("second-new"), List.of()));
            Thread.sleep(300);
            assertThat(second.isDone()).as("둘째 저장은 첫째가 끝날 때까지 잠금을 기다린다").isFalse();

            releaseFirstPublish.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.readCurrent(OWNED_PROFILE))
                .hasSize(30)
                .containsKey("first-new")
                .doesNotContainKey("second-new");
    }

    /** {@code skill-01} 부터 이름을 붙인 올린 스킬 {@code count} 개를 서비스를 거치지 않고 게시된 버전으로 둔다. */
    private void publishUploaded(int count) {
        Map<String, SkillBundle> bundles = new LinkedHashMap<>();
        for (int i = 1; i <= count; i++) {
            String name = String.format("skill-%02d", i);
            bundles.put(name, new SkillBundle(name, skillMd(name), List.of()));
        }
        store.markPublished(OWNED_PROFILE, store.writeVersion(OWNED_PROFILE, bundles));
    }

    /** 파일마다 10만 자 이하인 ASCII 본문으로 합계 바이트를 정확히 맞춘 참고 파일 목록을 만든다. */
    private static List<SkillFileInput> asciiFilesTotaling(long totalBytes, int fileCount) {
        List<SkillFileInput> files = new ArrayList<>();
        long remaining = totalBytes;
        for (int i = 1; i <= fileCount; i++) {
            int size = (int) Math.min(remaining, SkillService.MAX_CHARS_PER_FILE);
            files.add(new SkillFileInput("references/f" + i + ".md", "x".repeat(size)));
            remaining -= size;
        }
        if (remaining != 0) {
            throw new IllegalArgumentException("파일 " + fileCount + "개에 담을 수 없는 크기: " + totalBytes);
        }
        return files;
    }

    private static String skillMd(String name) {
        return skillMd(name, "이번 주 계획을 세운다");
    }

    private static String skillMd(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + name + "\n";
    }

    /** 앞머리 뒤에 본문이 없는 원문이다. */
    private static String frontmatterOnly(String name) {
        return "---\nname: " + name + "\ndescription: 이번 주 계획을 세운다\n---\n";
    }

    private static Agent agent(String code, String profile, AgentVisibility visibility, Long ownerId) {
        return Agent.of(
                code,
                code,
                profile,
                "http://agent-runtime.test/p/" + profile,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                ownerId,
                Instant.now());
    }

    private static List<String> versionDirs(String profile) {
        try (var stream = Files.list(SKILL_ROOT.resolve(profile))) {
            return stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("v"))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<String>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }
}
