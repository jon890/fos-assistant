package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.infra.HermesProfileKeyStore;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.people.application.HermesProfileProvisioner;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.application.dto.PersonAccess;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.quality.application.dto.EvaluationUserStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TrackingBackgroundTasks;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 평가 전용 사용자 준비가 사용자, 두 에이전트, 미리 넣는 기억을 하나씩 만들고, 기본 도구와 그림·사진 도구를 실행 공간 안에서만
 * 켜는지 본다. 첫 로그인의 기본 도구 쓰기와 겹쳐도 이름이 빠지지 않는지도 본다.
 *
 * <p>Hermes 대시보드와 toolset 은 대역이고 우리 표는 실제 저장소를 쓴다.
 */
@BackendIntegrationTest
class EvaluationUserServiceTest {

    private static final String PROFILE = "eval-check";

    @Autowired
    EvaluationUserService service;

    @Autowired
    PersonAccessService personAccess;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    MemoryRepository memories;

    @Autowired
    HermesProfileProvisioner profiles;

    @Autowired
    HermesToolsetClient toolsets;

    @Autowired
    HermesProfileKeyStore keyStore;

    @Autowired
    TrackingBackgroundTasks backgroundTasks;

    /** 대역이 기억하는 profile 마다 켜진 도구다. 실행 공간 쓰기만 그림·사진 도구를 켠다. */
    private final Map<String, List<String>> enabled = new ConcurrentHashMap<>();

    private CurrentUser admin;

    /** 켜면 첫 실행 공간 쓰기를 {@link #release} 가 열릴 때까지 붙잡는다. 첫 로그인의 기본 도구 쓰기가 늦게 닿는 경쟁을 만든다. */
    private volatile boolean holdFirst;

    private final AtomicBoolean firstWriteSeen = new AtomicBoolean();

    private final CountDownLatch release = new CountDownLatch(1);


    @BeforeEach
    void setUp() {
        agents.deleteAll();
        users.deleteAll();
        people.deleteAll();
        doNothing().when(profiles).provision(any());
        when(toolsets.readCatalog()).thenReturn(List.of(
            new ToolsetCatalogEntry("web", "웹", ""),
            new ToolsetCatalogEntry("terminal", "셸", ""),
            new ToolsetCatalogEntry("file", "파일", ""),
            new ToolsetCatalogEntry("code_execution", "코드 실행", ""),
            new ToolsetCatalogEntry("image_gen", "그림 만들기", ""),
            new ToolsetCatalogEntry("vision", "사진 보기", "")));
        when(toolsets.readEnabled(anyString(), anyString()))
            .thenAnswer(invocation -> enabled.getOrDefault(invocation.<String>getArgument(1), List.of("web", "skills")));
        // 실제 대시보드처럼 목록을 통째로 바꾼다. 나중에 쓴 쪽이 이긴다.
        doAnswer(invocation -> {
            holdFirstWrite();
            enabled.put(invocation.getArgument(0), List.copyOf(invocation.<List<String>>getArgument(1)));
            return null;
        }).when(toolsets).writeApiServerInSandbox(anyString(), anyList(), any());
        doAnswer(invocation -> {
            enabled.put(invocation.getArgument(0), List.copyOf(invocation.<List<String>>getArgument(1)));
            return null;
        }).when(toolsets).writeApiServer(anyString(), anyList(), any());
        AppUser saved = users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN));
        admin = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }


    @AfterEach
    void tearDown() {
        // 위임 대상 에이전트는 자기 profile 의 key 파일을 실제 디렉터리에 쓴다.
        agents.findAll().stream().map(Agent::hermesProfile).filter(profile -> !PROFILE.equals(profile)).forEach(keyStore::delete);
    }


    private void holdFirstWrite() throws InterruptedException {
        if (holdFirst && firstWriteSeen.compareAndSet(false, true)) {
            release.await(10, TimeUnit.SECONDS);
        }
    }


    private void awaitBackground() throws InterruptedException {
        backgroundTasks.awaitIdle(Duration.ofSeconds(20));
    }


    private AppUser evaluationUser() {
        return users.findFirstByGroupIdAndEvaluationTrueOrderByIdAsc(1L).orElseThrow();
    }


    private List<Agent> agentsOf(AppUser owner) {
        return agents.findAll().stream().filter(agent -> owner.id().equals(agent.ownerUserId())).toList();
    }


    @Test
    @DisplayName("준비하면 평가 사용자와 비공개 에이전트 둘, 미리 넣는 기억이 생기고 기본 도구와 그림·사진 도구가 켜져 준비됨이다")
    void prepareCreatesEverything() throws InterruptedException {
        service.prepare(admin, PROFILE);
        awaitBackground();

        AppUser evaluation = evaluationUser();
        assertThat(evaluation.email()).isEqualTo(EvaluationUserService.EMAIL);
        assertThat(evaluation.role()).isEqualTo(UserRole.MEMBER);
        assertThat(agentsOf(evaluation)).hasSize(2).allMatch(agent -> agent.visibility() == AgentVisibility.PRIVATE);
        assertThat(agentsOf(evaluation)).extracting(Agent::name).contains(EvaluationUserService.DELEGATE_AGENT_NAME);
        assertThat(memories.findAll()).filteredOn(memory -> evaluation.id().equals(memory.ownerUserId())).hasSize(1);
        EvaluationUserStatus status = service.status(admin);
        assertThat(status.ready()).isTrue();
        assertThat(status.hermesProfile()).isEqualTo(PROFILE);
        assertThat(enabled.get(PROFILE)).contains("terminal", "file", "code_execution", "image_gen", "vision");
    }


    @Test
    @DisplayName("첫 로그인의 기본 도구 쓰기가 준비보다 늦게 닿아도 셸·파일 도구와 그림·사진 도구가 모두 남는다")
    void lateDefaultToolsetWriteKeepsEverything() throws InterruptedException {
        holdFirst = true;

        service.prepare(admin, PROFILE);
        release.countDown();
        awaitBackground();

        assertThat(firstWriteSeen).isTrue();
        assertThat(enabled.get(PROFILE)).contains("terminal", "file", "code_execution", "image_gen", "vision");
        assertThat(service.status(admin).ready()).isTrue();
    }


    @Test
    @DisplayName("켜졌던 셸 도구가 빠지면 상태가 준비되지 않음을 알리고 다시 준비하면 채운다")
    void missingShellToolIsReportedAndRefilled() throws InterruptedException {
        service.prepare(admin, PROFILE);
        awaitBackground();
        enabled.put(PROFILE, enabled.get(PROFILE).stream().filter(name -> !"terminal".equals(name)).toList());

        assertThat(service.status(admin).missing())
            .containsExactly(EvaluationUserStatus.Missing.POLICY_REGISTRATION_REQUIRED.name());

        assertThat(service.prepare(admin, PROFILE).ready()).isTrue();
        assertThat(enabled.get(PROFILE)).contains("terminal");
    }


    @Test
    @DisplayName("Hermes 가 도구 목록을 돌려주지 않으면 상태가 정책 등록 필요가 아니라 도구 목록 읽기 실패를 알린다")
    void unreadableToolsAreReportedSeparately() throws InterruptedException {
        service.prepare(admin, PROFILE);
        awaitBackground();
        when(toolsets.readEnabled(anyString(), anyString())).thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThat(service.status(admin).missing()).containsExactly(EvaluationUserStatus.Missing.TOOLS_UNREADABLE.name());
    }


    @Test
    @DisplayName("두 번 준비해도 사용자, 에이전트, 기억이 하나씩이다")
    void prepareTwiceKeepsOne() throws InterruptedException {
        service.prepare(admin, PROFILE);
        awaitBackground();
        service.prepare(admin, "other-profile");

        AppUser evaluation = evaluationUser();
        assertThat(users.findAll()).filteredOn(AppUser::evaluation).hasSize(1);
        assertThat(agentsOf(evaluation)).hasSize(2);
        assertThat(memories.findAll()).filteredOn(memory -> evaluation.id().equals(memory.ownerUserId())).hasSize(1);
        assertThat(service.status(admin).hermesProfile()).isEqualTo(PROFILE);
    }


    @Test
    @DisplayName("실행 공간이 없으면 준비는 끝나고 정책 등록 필요를 알리며 셸 도구를 실행 공간 밖으로 켜지 않는다")
    void sandboxUnavailableReportsPolicyRegistration() throws InterruptedException {
        doThrow(new ApiException(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, "no sandbox"))
            .when(toolsets).writeApiServerInSandbox(anyString(), anyList(), any());

        service.prepare(admin, PROFILE);
        awaitBackground();

        assertThat(service.status(admin).missing())
            .containsExactly(EvaluationUserStatus.Missing.POLICY_REGISTRATION_REQUIRED.name());
        assertThat(agentsOf(evaluationUser())).hasSize(2);
        verify(toolsets, never()).writeApiServer(
            eq(PROFILE), argThat(names -> names.contains("terminal") || names.contains("file")), any());
    }


    @Test
    @DisplayName("평가 사용자가 없으면 상태가 사용자 없음이다")
    void statusWithoutUser() {
        assertThat(service.status(admin).missing()).containsExactly(EvaluationUserStatus.Missing.USER.name());
    }


    @Test
    @DisplayName("관리자 사용자 목록 항목이 평가 사용자를 평가용으로 표시한다")
    void personAccessMarksEvaluation() throws InterruptedException {
        service.prepare(admin, PROFILE);
        awaitBackground();

        List<PersonAccess> listed = new ArrayList<>(personAccess.list());

        assertThat(listed).filteredOn(entry -> entry.person().email().equals(EvaluationUserService.EMAIL))
            .singleElement()
            .matches(PersonAccess::evaluation);
    }
}
