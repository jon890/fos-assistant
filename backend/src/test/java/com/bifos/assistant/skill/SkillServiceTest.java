package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesRequestRejected;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillBundle;
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
import com.bifos.assistant.user.domain.UserRole;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    /** {@code application-test.yml} 의 스킬 뿌리다. 두 뿌리가 같아 게시된 경로를 그대로 열 수 있다. */
    private static final Path SKILL_ROOT = Path.of("build/test-skills");

    private static final List<String> WITHOUT_SKILLS = List.of("web", "fos-assistant");
    private static final List<String> WITH_SKILLS = List.of("web", "skills", "fos-assistant");

    @Autowired SkillService skills;
    @Autowired SkillStore store;
    @Autowired AgentRepository agents;

    @MockitoBean HermesSkillClient skillClient;
    @MockitoBean HermesToolsetClient toolsets;

    /** 스킬 커맨드의 캐시를 비우는 {@link SkillsChanged} 를 서비스가 냈는지 본다. */
    @Autowired ApplicationEvents applicationEvents;

    @BeforeEach
    void 준비한다() {
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
    void 저장하면_한_번의_게시에_새_경로와_skills_가_든_도구_목록이_함께_간다() throws Exception {
        SkillDetail saved = skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"),
                List.of(new SkillFileInput("references/guide.md", "안내")));

        String version = store.currentVersion(OWNED_PROFILE).orElseThrow();
        ArgumentCaptor<List<String>> dirs = captor();
        ArgumentCaptor<List<String>> apiServer = captor();
        verify(skillClient).publish(eq(OWNED_PROFILE), dirs.capture(), apiServer.capture());
        assertThat(dirs.getValue()).containsExactly(store.agentPath(OWNED_PROFILE, version));
        assertThat(apiServer.getValue()).containsExactly("web", "skills", "fos-assistant");
        assertThat(saved).isEqualTo(new SkillDetail("weekly-plan", "이번 주 계획을 세운다", skillMd("weekly-plan"),
                List.of(new SkillFileInfo("references/guide.md", 6L))));
        assertThat(Files.readAllLines(Path.of(dirs.getValue().get(0), "weekly-plan", "SKILL.md")))
                .as("게시한 경로에 SKILL.md 가 있다")
                .contains("name: weekly-plan");
    }

    @Test
    void skills_가_이미_켜져_있으면_도구_목록은_null_이다() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(WITH_SKILLS);

        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());

        verify(skillClient).publish(eq(OWNED_PROFILE), anyList(), isNull());
        verify(toolsets, never()).writeApiServer(anyString(), anyList());
    }

    @Test
    void 게시가_4xx_로_거절되면_새_디렉터리가_없고_지금_버전이_그대로다() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        String before = store.currentVersion(OWNED_PROFILE).orElseThrow();
        doThrow(new HermesRequestRejected(ErrorCode.HERMES_UNAVAILABLE, "rejected",
                new HttpClientErrorException(HttpStatus.BAD_REQUEST)))
                .when(skillClient).publish(anyString(), anyList(), any());

        assertCode(() -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);

        assertThat(store.currentVersion(OWNED_PROFILE)).contains(before);
        assertThat(versionDirs(OWNED_PROFILE)).as("거절된 버전 디렉터리는 지운다").containsExactly(before);
        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("weekly-plan");
    }

    @Test
    void 게시가_timeout_이나_5xx_로_실패하면_표식_없이_남고_다음_성공이_그것을_지우며_실패한_변경은_없다() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        String first = store.currentVersion(OWNED_PROFILE).orElseThrow();
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "read timed out"))
                .when(skillClient).publish(anyString(), anyList(), any());

        assertCode(() -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);

        assertThat(store.currentVersion(OWNED_PROFILE)).as("지금 버전은 옛 것이다").contains(first);
        assertThat(versionDirs(OWNED_PROFILE)).as("실패한 버전은 표식 없이 남는다").hasSize(2);

        doAnswer(call -> null).when(skillClient).publish(anyString(), anyList(), any());
        skills.save(OWNER, OWNED, "cooking", skillMd("cooking"), List.of());

        String third = store.currentVersion(OWNED_PROFILE).orElseThrow();
        assertThat(versionDirs(OWNED_PROFILE)).as("표식 없는 디렉터리는 다음 성공이 지운다").containsExactlyInAnyOrder(first, third);
        assertThat(store.readCurrent(OWNED_PROFILE)).as("실패한 저장의 변경은 반영되지 않는다")
                .containsOnlyKeys("cooking", "weekly-plan");
    }

    @Test
    void timeout_뒤_Hermes_에_뜬_새_스킬은_올린_스킬로_보이고_같은_이름으로_다시_저장된다() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "read timed out"))
                .when(skillClient).publish(anyString(), anyList(), any());
        assertCode(() -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"),
                List.of(new SkillFileInput("references/list.md", "장보기 목록"))), ErrorCode.HERMES_UNAVAILABLE);
        // Hermes 는 응답만 잃고 그 버전을 반영해 목록에 새 이름을 보인다.
        when(skillClient.list(OWNED_PROFILE)).thenReturn(List.of(
                new HermesSkill("hermes-help", "Hermes 기본", true),
                new HermesSkill("shopping", "shopping 을 한다", true),
                new HermesSkill("weekly-plan", "이번 주 계획을 세운다", true)));

        assertThat(skills.list(OWNER, OWNED).skills()).as("Hermes 목록의 새 이름은 올린 스킬이다").contains(
                new SkillListItem("shopping", "shopping 을 한다", SkillSource.UPLOADED, true,
                        new SkillUsageSummary(0, null)));
        assertThat(skills.read(OWNER, OWNED, "shopping").files())
                .as("편집 화면이 표식 없는 버전의 원문을 연다")
                .containsExactly(new SkillFileInfo("references/list.md", 16L));

        doAnswer(call -> null).when(skillClient).publish(anyString(), anyList(), any());
        skills.save(OWNER, OWNED, "shopping", skillMd("shopping"),
                List.of(new SkillFileInput("references/list.md", null)));

        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("shopping", "weekly-plan");
        assertThat(store.readCurrent(OWNED_PROFILE).get("shopping").files())
                .as("본문을 생략한 파일은 표식 없는 버전의 내용을 쓴다")
                .containsExactly(new SkillFile("references/list.md", "장보기 목록"));
        assertThat(store.readPending(OWNED_PROFILE)).isEmpty();
    }

    @Test
    void timeout_뒤_Hermes_에만_뜬_스킬을_지우면_지금_버전을_다시_게시하고_지금_버전이_없으면_빈_목록을_게시한다() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "read timed out"))
                .when(skillClient).publish(anyString(), anyList(), any());
        assertCode(() -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);
        assertCode(() -> skills.save(OWNER, GROUP, "cooking", skillMd("cooking"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);
        doAnswer(call -> null).when(skillClient).publish(anyString(), anyList(), any());

        skills.delete(OWNER, OWNED, "shopping");

        String republished = store.currentVersion(OWNED_PROFILE).orElseThrow();
        verify(skillClient).publish(eq(OWNED_PROFILE), eq(List.of(store.agentPath(OWNED_PROFILE, republished))), any());
        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("weekly-plan");
        assertThat(store.readPending(OWNED_PROFILE)).as("Hermes 가 벗어난 표식 없는 버전은 지운다").isEmpty();
        assertCode(() -> skills.delete(OWNER, OWNED, "shopping"), ErrorCode.SKILL_NOT_FOUND);

        skills.delete(OWNER, GROUP, "cooking");

        verify(skillClient).publish(GROUP_PROFILE, List.of(), null);
        assertThat(SKILL_ROOT.resolve(GROUP_PROFILE)).doesNotExist();
    }

    @Test
    void 저장과_켜고_끄기와_지우기가_Hermes_에_반영되면_그_에이전트의_SkillsChanged_를_낸다() {
        Long agentId = agents.findByCode(OWNED).orElseThrow().id();

        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of());
        skills.toggle(OWNER, OWNED, "weekly-plan", false);
        skills.delete(OWNER, OWNED, "weekly-plan");
        skills.delete(OWNER, OWNED, "shopping");

        assertThat(applicationEvents.stream(SkillsChanged.class))
                .as("저장 둘, 켜고 끄기 하나, 지우기 둘(마지막 스킬 포함)")
                .containsExactly(
                        new SkillsChanged(agentId), new SkillsChanged(agentId), new SkillsChanged(agentId),
                        new SkillsChanged(agentId), new SkillsChanged(agentId));
    }

    @Test
    void Hermes_가_거절해_저장과_켜고_끄기와_지우기가_실패하면_SkillsChanged_를_내지_않는다() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of());
        applicationEvents.clear();
        HermesRequestRejected rejected = new HermesRequestRejected(ErrorCode.HERMES_UNAVAILABLE, "rejected",
                new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        doThrow(rejected).when(skillClient).publish(anyString(), anyList(), any());
        doThrow(rejected).when(skillClient).toggle(anyString(), anyString(), anyBoolean());

        assertCode(() -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()),
                ErrorCode.HERMES_UNAVAILABLE);
        assertCode(() -> skills.toggle(OWNER, OWNED, "weekly-plan", false), ErrorCode.HERMES_UNAVAILABLE);
        assertCode(() -> skills.delete(OWNER, OWNED, "weekly-plan"), ErrorCode.HERMES_UNAVAILABLE);

        assertThat(applicationEvents.stream(SkillsChanged.class)).isEmpty();
    }

    @Test
    void 편집자가_아니면_FORBIDDEN_이고_아무것도_쓰지_않는다() {
        assertCode(() -> skills.save(MEMBER, GROUP, "weekly-plan", skillMd("weekly-plan"), List.of()),
                ErrorCode.FORBIDDEN);
        assertCode(() -> skills.read(MEMBER, GROUP, "weekly-plan"), ErrorCode.FORBIDDEN);
        assertCode(() -> skills.delete(MEMBER, GROUP, "weekly-plan"), ErrorCode.FORBIDDEN);
        assertCode(() -> skills.toggle(MEMBER, GROUP, "hermes-help", false), ErrorCode.FORBIDDEN);
        assertCode(() -> skills.save(MEMBER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of()),
                ErrorCode.AGENT_NOT_FOUND);

        verify(skillClient, never()).publish(anyString(), anyList(), any());
        assertThat(store.currentVersion(GROUP_PROFILE)).isEmpty();
    }

    @Test
    void 입력_규칙을_어기면_VALIDATION_FAILED_이고_Hermes_이름과_같으면_SKILL_NAME_TAKEN_이다() {
        assertCode(() -> skills.save(OWNER, OWNED, "weekly-plan", skillMd("other-name"), List.of()),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"),
                List.of(new SkillFileInput("scripts/run.sh", "echo"))), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"),
                IntStream.rangeClosed(1, 21)
                        .mapToObj(i -> new SkillFileInput("references/f" + i + ".md", "x"))
                        .toList()), ErrorCode.VALIDATION_FAILED);
        String hundredThousand = "a".repeat(100_000);
        assertCode(() -> skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"),
                IntStream.rangeClosed(1, 11)
                        .mapToObj(i -> new SkillFileInput("references/f" + i + ".md", hundredThousand))
                        .toList()), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> skills.save(OWNER, OWNED, "new", skillMd("new"), List.of()), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> skills.save(OWNER, OWNED, "weekly-plan", "# 앞머리가 없다", List.of()),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> skills.save(OWNER, OWNED, "hermes-help", skillMd("hermes-help"), List.of()),
                ErrorCode.SKILL_NAME_TAKEN);

        verify(skillClient, never()).publish(anyString(), anyList(), any());
        assertThat(store.currentVersion(OWNED_PROFILE)).isEmpty();
    }

    @Test
    void 정확히_20개_파일과_정확히_1_MiB_는_받고_1_바이트가_넘으면_거절한다() {
        String skillMd = skillMd("weekly-plan");
        long fileBytes = SkillService.MAX_TOTAL_BYTES - skillMd.getBytes(StandardCharsets.UTF_8).length;

        SkillDetail saved = skills.save(OWNER, OWNED, "weekly-plan", skillMd, asciiFilesTotaling(fileBytes, 20));

        assertThat(saved.files()).hasSize(20);
        assertThat(saved.files().stream().mapToLong(SkillFileInfo::size).sum() + skillMd.getBytes(StandardCharsets.UTF_8).length)
                .isEqualTo(SkillService.MAX_TOTAL_BYTES);
        assertCode(() -> skills.save(OWNER, OWNED, "weekly-plan", skillMd, asciiFilesTotaling(fileBytes + 1, 20)),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 본문을_생략한_파일은_지금_내용이_그대로_새_버전에_있고_없는_경로면_거절한다() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"),
                List.of(new SkillFileInput("references/guide.md", "처음 안내")));

        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan") + "\n고침",
                List.of(new SkillFileInput("references/guide.md", null),
                        new SkillFileInput("templates/note.md", "새 파일")));

        SkillBundle current = store.readCurrent(OWNED_PROFILE).get("weekly-plan");
        assertThat(current.skillMd()).endsWith("고침");
        assertThat(current.files()).containsExactly(
                new SkillFile("references/guide.md", "처음 안내"),
                new SkillFile("templates/note.md", "새 파일"));
        assertCode(() -> skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"),
                List.of(new SkillFileInput("references/missing.md", null))), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 읽기는_앞머리를_포함한_원문과_UTF_8_크기를_주고_목록은_출처를_붙인다() {
        skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"),
                List.of(new SkillFileInput("references/guide.md", "안내")));
        when(skillClient.list(OWNED_PROFILE)).thenReturn(List.of(
                new HermesSkill("hermes-help", "Hermes 기본", true),
                new HermesSkill("weekly-plan", "이번 주 계획을 세운다", false)));

        SkillDetail detail = skills.read(OWNER, OWNED, "weekly-plan");
        assertThat(detail.body()).startsWith("---\nname: weekly-plan");
        assertThat(detail.description()).isEqualTo("이번 주 계획을 세운다");
        assertThat(detail.files()).containsExactly(new SkillFileInfo("references/guide.md", 6L));
        assertCode(() -> skills.read(OWNER, OWNED, "hermes-help"), ErrorCode.SKILL_NOT_FOUND);

        SkillList list = skills.list(OWNER, OWNED);
        assertThat(list.editable()).isTrue();
        assertThat(list.skillsToolsetEnabled()).as("대역의 켜진 목록에 skills 가 없다").isFalse();
        // 편집자에게는 호출이 없는 스킬에도 합계가 0 으로 붙는다.
        SkillUsageSummary noUse = new SkillUsageSummary(0, null);
        assertThat(list.skills()).containsExactly(
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
    void 지우면_그_스킬이_빠진_버전이_게시되고_마지막_스킬을_지우면_빈_목록이_게시된다() {
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
    void 켜고_끄기는_Hermes_이름_규칙으로_보고_대시보드에_그대로_넘긴다() {
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
    void 같은_에이전트에_두_저장이_동시에_와도_둘_다_반영된_버전이_남는다() throws Exception {
        CountDownLatch firstPublishStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstPublish = new CountDownLatch(1);
        doAnswer(call -> {
            if (firstPublishStarted.getCount() > 0) {
                firstPublishStarted.countDown();
                assertThat(releaseFirstPublish.await(10, TimeUnit.SECONDS)).as("첫 게시를 풀어 주기까지").isTrue();
            }
            return null;
        }).when(skillClient).publish(anyString(), anyList(), any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SkillDetail> first = pool.submit(
                    () -> skills.save(OWNER, OWNED, "weekly-plan", skillMd("weekly-plan"), List.of()));
            assertThat(firstPublishStarted.await(10, TimeUnit.SECONDS)).as("첫 저장이 게시에 닿기까지").isTrue();
            Future<SkillDetail> second = pool.submit(
                    () -> skills.save(OWNER, OWNED, "shopping", skillMd("shopping"), List.of()));
            // 둘째가 잠금에 걸려 있는 동안에는 게시가 한 번뿐이다.
            Thread.sleep(300);
            verify(skillClient, org.mockito.Mockito.times(1)).publish(anyString(), anyList(), any());
            assertThat(second.isDone()).as("둘째 저장은 첫째가 끝날 때까지 기다린다").isFalse();

            releaseFirstPublish.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.readCurrent(OWNED_PROFILE)).containsOnlyKeys("shopping", "weekly-plan");
        verify(skillClient, org.mockito.Mockito.times(2)).publish(eq(OWNED_PROFILE), anyList(), any());
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
        return "---\nname: " + name + "\ndescription: 이번 주 계획을 세운다\n---\n# " + name + "\n";
    }

    private static Agent agent(String code, String profile, AgentVisibility visibility, Long ownerId) {
        return Agent.of(code, code, profile, "http://agent-runtime.test/p/" + profile,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, visibility, ownerId);
    }

    private static List<String> versionDirs(String profile) {
        try (var stream = Files.list(SKILL_ROOT.resolve(profile))) {
            return stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("v"))
                    .toList();
        } catch (java.io.IOException ex) {
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
