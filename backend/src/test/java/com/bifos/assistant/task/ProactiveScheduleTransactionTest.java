package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.proactive.application.model.CheckBlockerCode;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import com.bifos.assistant.task.application.ProactiveScheduleService;
import com.bifos.assistant.task.application.TaskFiring;
import com.bifos.assistant.task.application.TaskRunStarter;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Hermes 준비 상태를 읽지 못해도 매일 깨우기를 끈 트랜잭션이 커밋되고, 이후 발화가 새 살펴보기를 열지 않는지 실제 DB로 본다.
 */
@BackendIntegrationTest
class ProactiveScheduleTransactionTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant DUE = Instant.parse("2026-11-01T00:01:00Z");

    @Autowired
    HermesToolsetClient toolsets;

    @MockitoBean
    SkillCommandCatalog skills;

    @Autowired
    ProactiveScheduleService schedules;

    @Autowired
    TaskFiring firing;

    @Autowired
    TaskRunStarter starter;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskTriggerRepository triggers;

    @Autowired
    TaskRunRepository runs;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    private final List<Long> createdRuns = new ArrayList<>();
    private final List<Long> createdTriggers = new ArrayList<>();
    private final List<Long> createdTasks = new ArrayList<>();
    private final List<Long> createdAgents = new ArrayList<>();
    private final List<Long> createdUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(skills.enabledNames(any(Agent.class))).thenReturn(Set.of("proactive-check"));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
    }

    @AfterEach
    void tearDown() {
        runs.deleteAllById(createdRuns);
        triggers.deleteAllById(createdTriggers);
        tasks.deleteAllById(createdTasks);
        agents.deleteAllById(createdAgents);
        users.deleteAllById(createdUsers);
    }

    @ParameterizedTest(name = "{0} 중에도 매일 깨우기를 끈다")
    @MethodSource("runtimeFailures")
    @DisplayName("Hermes 장애 중에도 ACTIVE 매일 깨우기를 끄면 DB에 PAUSED로 커밋되고 복구 뒤 새 CHECK를 시작하지 않는다")
    void disablesScheduleAndPreventsFutureChecksAfterRuntimeRecovers(String band, ErrorCode errorCode) {
        Fixture fixture = activeSchedule();
        TaskTrigger trigger = triggers.findByTaskId(fixture.task().id()).orElseThrow();
        trigger.moveNext(DUE, NOW);
        triggers.saveAndFlush(trigger);
        TaskRun queued = runs.saveAndFlush(TaskRun.queued(
                fixture.task().id(), trigger.id(), fixture.owner().id(), DUE, NOW));
        createdRuns.add(queued.id());

        reset(toolsets, skills);
        when(toolsets.readEnabled(anyString(), anyString())).thenThrow(new ApiException(errorCode, "Hermes " + band));
        when(skills.enabledNames(any(Agent.class))).thenReturn(Set.of("proactive-check"));

        var read = schedules.get(fixture.currentUser(), fixture.agent().code());

        assertThatThrownBy(() ->
                        schedules.update(fixture.currentUser(), fixture.agent().code(), true, "09:00", "Asia/Seoul"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(errorCode);
        assertThatThrownBy(() -> schedules.schedulingAvailable(
                        fixture.currentUser(), fixture.agent().code()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(errorCode);

        clearInvocations(toolsets, skills);
        var disabled = schedules.update(fixture.currentUser(), fixture.agent().code(), false, "09:00", "Asia/Seoul");

        assertThat(read.enabled()).isTrue();
        assertThat(read.time()).isEqualTo("09:00");
        assertThat(read.blockers())
                .extracting(blocker -> blocker.code())
                .containsExactly(CheckBlockerCode.READINESS_UNKNOWN);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.time()).isEqualTo("09:00");
        assertThat(disabled.blockers())
                .extracting(blocker -> blocker.code())
                .containsExactly(CheckBlockerCode.READINESS_UNKNOWN);
        assertThat(tasks.findById(fixture.task().id()).orElseThrow().state()).isEqualTo(TaskState.PAUSED);
        verifyNoInteractions(toolsets, skills);

        reset(toolsets, skills);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skills.enabledNames(any(Agent.class))).thenReturn(Set.of("proactive-check"));

        assertThat(firing.fireDue(DUE)).as("복구 뒤 새 CHECK 발화").isZero();
        assertThat(starter.startQueued(DUE)).as("기존 QUEUED CHECK 시작").isZero();
        assertThat(runs.findById(queued.id()).orElseThrow())
                .extracting(TaskRun::status, TaskRun::reason, TaskRun::proactiveCheckId)
                .containsExactly(TaskRunStatus.SKIPPED, TaskRunReason.PAUSED, null);
        assertThat(checks.findFirstByUserIdAndAgentIdOrderByIdDesc(
                        fixture.owner().id(), fixture.agent().id()))
                .isEmpty();
    }

    private Fixture activeSchedule() {
        String suffix = UUID.randomUUID().toString();
        String email = "schedule-" + suffix + "@example.com";
        AppUser owner = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(owner.id());
        Agent agent = agents.save(Agent.of(
                "schedule-" + suffix,
                "일정 에이전트",
                "schedule-" + suffix,
                "http://agent-runtime.test/p/" + suffix,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
        createdAgents.add(agent.id());
        CurrentUser currentUser =
                new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());

        assertThat(schedules
                        .update(currentUser, agent.code(), true, "09:00", "Asia/Seoul")
                        .enabled())
                .isTrue();
        Task task = tasks.findByOwnerUserIdAndAgentIdAndKind(owner.id(), agent.id(), TaskKind.CHECK)
                .orElseThrow();
        createdTasks.add(task.id());
        createdTriggers.add(triggers.findByTaskId(task.id()).orElseThrow().id());
        return new Fixture(owner, agent, currentUser, task);
    }

    private static Stream<Arguments> runtimeFailures() {
        return Stream.of(
                Arguments.of("timeout", ErrorCode.HERMES_UNAVAILABLE),
                Arguments.of("429", ErrorCode.HERMES_BUSY),
                Arguments.of("503", ErrorCode.HERMES_UNAVAILABLE));
    }

    private record Fixture(AppUser owner, Agent agent, CurrentUser currentUser, Task task) {}
}
