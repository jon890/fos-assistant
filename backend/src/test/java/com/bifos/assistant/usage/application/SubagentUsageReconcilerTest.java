package com.bifos.assistant.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.ProfileModelDefaultsClient;
import com.bifos.assistant.hermes.dto.ProfileModelDefaults;
import com.bifos.assistant.hermes.dto.SubagentSessionUsage;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.domain.SubagentUsageJob;
import com.bifos.assistant.usage.domain.type.ReasoningEffortSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.usage.infra.SubagentUsageJobRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** 자식 session 응답이 완료 사건으로 바뀔 수 있는지의 필수 조건을 고정한다. */
class SubagentUsageReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:10Z");

    @Test
    @DisplayName("아직 끝나지 않은 자식은 재조회 대상으로 남는다")
    void unfinishedChildIsNotFinal() throws ReflectiveOperationException {
        SubagentUsageJob job = job();
        SubagentSessionUsage running =
                new SubagentSessionUsage("child", "subagent", "parent", "example-fast", 1.0, null, 10L, 2L, 1L, 1L);

        assertThat(SubagentUsageReconciler.isFinalChild(job, running)).isFalse();
    }

    @Test
    @DisplayName("다른 부모에 속한 종료 session은 완료 사용량으로 기록하지 않는다")
    void rejectsChildOfAnotherParent() throws ReflectiveOperationException {
        SubagentUsageJob job = job();
        SubagentSessionUsage wrongParent = new SubagentSessionUsage(
                "child", "subagent", "other-parent", "example-fast", 1.0, 2.0, 10L, 2L, 1L, 1L);

        assertThat(SubagentUsageReconciler.isFinalChild(job, wrongParent)).isFalse();
    }

    @Test
    @DisplayName("종료한 같은 자식만 캐시를 포함한 입력 토큰과 소요 시간을 기록할 수 있다")
    void acceptsCompletedChildWithInclusiveUsage() throws ReflectiveOperationException {
        SubagentUsageJob job = job();
        SubagentSessionUsage completed =
                new SubagentSessionUsage("child", "subagent", "parent", "example-fast", 1.25, 3.75, 10L, 4L, 2L, 3L);

        assertThat(SubagentUsageReconciler.isFinalChild(job, completed)).isTrue();
        assertThat(completed.inclusiveInputTokens()).isEqualTo(15L);
        assertThat(completed.durationMs()).isEqualTo(2_500L);
    }

    @Test
    @DisplayName("토큰 합계가 long 범위를 넘으면 기록하지 않는다")
    void keepsOverflowingInclusiveInputUnknown() {
        SubagentSessionUsage completed = new SubagentSessionUsage(
                "child", "subagent", "parent", "example-fast", 1.0, 2.0, Long.MAX_VALUE, 0L, 1L, 0L);

        assertThat(completed.inclusiveInputTokens()).isNull();
    }

    @Test
    @DisplayName("정상 기본값 응답에 effort가 없어도 확인 시각을 적어 재조회를 멈춘다")
    void supplementMarksCheckedWhenDefaultResponseHasNoEffort() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.defaults.read("dad")).thenReturn(new ProfileModelDefaults("openai-codex", "example-fast", null));

        fixtures.reconciler.supplementDefault(1L, "dad");

        assertThat(fixtures.parent.reasoningEffort()).isNull();
        assertThat(fixtures.parent.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.UNKNOWN);
        assertThat(fixtures.parent.reasoningDefaultsCheckedAt()).isEqualTo(NOW);
        verify(fixtures.executions).save(fixtures.parent);
    }

    @Test
    @DisplayName("기본값 조회가 실패하면 확인 시각 없이 다음 작업에서 다시 조회한다")
    void supplementLeavesUncheckedWhenDefaultReadFails() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        when(fixtures.defaults.read("dad")).thenReturn(null);

        fixtures.reconciler.supplementDefault(1L, "dad");

        assertThat(fixtures.parent.reasoningDefaultsCheckedAt()).isNull();
        verify(fixtures.executions, never()).lockById(anyLong());
        verify(fixtures.executions, never()).save(any());
    }

    @Test
    @DisplayName("기본값 없음 확인 뒤 늦게 온 effort 응답은 같은 실행을 바꾸지 않는다")
    void supplementDoesNotConsumeLateDefaultAfterChecked() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.defaults.read("dad"))
                .thenReturn(
                        new ProfileModelDefaults("openai-codex", "example-fast", null),
                        new ProfileModelDefaults("openai-codex", "example-fast", "high"));

        fixtures.reconciler.supplementDefault(1L, "dad");
        fixtures.reconciler.supplementDefault(1L, "dad");

        assertThat(fixtures.parent.reasoningEffort()).isNull();
        assertThat(fixtures.parent.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.UNKNOWN);
        assertThat(fixtures.parent.reasoningDefaultsCheckedAt()).isEqualTo(NOW);
        verify(fixtures.executions).save(fixtures.parent);
    }

    @Test
    @DisplayName("자식 작업이 밀려도 한 tick은 profile 하나와 자식 셋을 전역 네 슬롯에 함께 넣는다")
    void reconcileReservesOneOfFourSlotsForProfileDefault() throws Exception {
        Fixtures fixtures = fixtures();
        SubagentUsageJob second = jobWithId(11L);
        SubagentUsageJob third = jobWithId(12L);
        SubagentUsageJob fourth = jobWithId(13L);
        CountDownLatch profileStarted = new CountDownLatch(1);
        CountDownLatch childrenStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        when(fixtures.executions.findUnknownReasoningDefaults(any(), any())).thenReturn(List.of(fixtures.parent));
        when(fixtures.jobs.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(any(), any()))
                .thenReturn(List.of(fixtures.job, second, third, fourth));
        when(fixtures.jobs.findById(10L)).thenReturn(Optional.of(fixtures.job));
        when(fixtures.jobs.findById(11L)).thenReturn(Optional.of(second));
        when(fixtures.jobs.findById(12L)).thenReturn(Optional.of(third));
        when(fixtures.jobs.findById(13L)).thenReturn(Optional.of(fourth));
        when(fixtures.defaults.read("dad")).thenAnswer(invocation -> {
            profileStarted.countDown();
            await(release);
            return new ProfileModelDefaults("openai-codex", "example-fast", null);
        });
        when(fixtures.hermes.readSubagentUsage(any(), any(), any())).thenAnswer(invocation -> {
            childrenStarted.countDown();
            await(release);
            return null;
        });

        try {
            fixtures.reconciler.reconcile();

            assertThat(profileStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(childrenStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(childrenStarted.getCount()).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("재기동 뒤 종료 부모의 시작 사건에서 누락 작업을 한 번 만든다")
    void discoverCreatesMissingJobForFinishedParent() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        when(fixtures.events.findUnscheduledChildren(any(), any())).thenReturn(List.of(fixtures.start));
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.agents.findById(2L)).thenReturn(Optional.of(fixtures.agent));
        when(fixtures.jobs.existsByExecutionIdAndChildSessionId(1L, "child")).thenReturn(false);

        fixtures.reconciler.discover(NOW);

        ArgumentCaptor<SubagentUsageJob> job = ArgumentCaptor.forClass(SubagentUsageJob.class);
        verify(fixtures.jobs).save(job.capture());
        assertThat(fixtures.agent.enabled()).isTrue();
        assertThat(fixtures.agent.isDeleted()).isFalse();
        assertThat(job.getValue().status()).isEqualTo("WAITING");
        assertThat(job.getValue().unconfirmedReason()).isNull();
    }

    @Test
    @DisplayName("시작 사건의 에이전트가 사라졌으면 만료 작업을 남겨 다시 발견하지 않는다")
    void discoverExpiresJobWhenAgentIsMissing() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        when(fixtures.events.findUnscheduledChildren(any(), any())).thenReturn(List.of(fixtures.start));
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.agents.findById(2L)).thenReturn(Optional.empty());
        when(fixtures.jobs.existsByExecutionIdAndChildSessionId(1L, "child")).thenReturn(false);

        fixtures.reconciler.discover(NOW);

        ArgumentCaptor<SubagentUsageJob> job = ArgumentCaptor.forClass(SubagentUsageJob.class);
        verify(fixtures.jobs).save(job.capture());
        assertThat(job.getValue().status()).isEqualTo("EXPIRED");
        assertThat(job.getValue().unconfirmedReason()).isEqualTo("AGENT_MISSING");
    }

    @Test
    @DisplayName("지운 에이전트의 시작 사건도 AGENT_MISSING 만료 작업을 남긴다")
    void discoverExpiresJobWhenAgentIsDeleted() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        setField(fixtures.agent, "deletedAt", NOW);
        when(fixtures.events.findUnscheduledChildren(any(), any())).thenReturn(List.of(fixtures.start));
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.agents.findById(2L)).thenReturn(Optional.of(fixtures.agent));
        when(fixtures.jobs.existsByExecutionIdAndChildSessionId(1L, "child")).thenReturn(false);

        fixtures.reconciler.discover(NOW);

        ArgumentCaptor<SubagentUsageJob> job = ArgumentCaptor.forClass(SubagentUsageJob.class);
        verify(fixtures.jobs).save(job.capture());
        assertThat(job.getValue().status()).isEqualTo("EXPIRED");
        assertThat(job.getValue().unconfirmedReason()).isEqualTo("AGENT_MISSING");
    }

    @Test
    @DisplayName("에이전트 profile이 바뀌었으면 만료 작업을 남겨 다시 발견하지 않는다")
    void discoverExpiresJobWhenProfileChanged() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        Agent agentWithChangedProfile = Agent.of(
                "agent",
                "에이전트",
                "changed",
                "http://runtime",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L);
        setField(agentWithChangedProfile, "id", 2L);
        when(fixtures.events.findUnscheduledChildren(any(), any())).thenReturn(List.of(fixtures.start));
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.agents.findById(2L)).thenReturn(Optional.of(agentWithChangedProfile));
        when(fixtures.jobs.existsByExecutionIdAndChildSessionId(1L, "child")).thenReturn(false);

        fixtures.reconciler.discover(NOW);

        ArgumentCaptor<SubagentUsageJob> job = ArgumentCaptor.forClass(SubagentUsageJob.class);
        verify(fixtures.jobs).save(job.capture());
        assertThat(job.getValue().status()).isEqualTo("EXPIRED");
        assertThat(job.getValue().unconfirmedReason()).isEqualTo("PROFILE_CHANGED");
    }

    @Test
    @DisplayName("종료 자식은 완료 사건 하나와 DONE 작업을 남긴다")
    void pollRecordsFinalUsageOnlyOnce() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        when(fixtures.jobs.findById(10L)).thenReturn(Optional.of(fixtures.job));
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.events.existsByExecutionIdAndHermesSessionIdAndEventType(
                        1L, "child", ExecutionEventType.SUBAGENT_COMPLETED))
                .thenReturn(false);
        when(fixtures.events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(1L)))
                .thenReturn(List.of(fixtures.start));
        when(fixtures.events.lastSequence(1L)).thenReturn(7);
        when(fixtures.hermes.readSubagentUsage(any(), any(), any()))
                .thenReturn(new SubagentSessionUsage(
                        "child", "subagent", "parent", "example-fast", 1.0, 2.5, 10L, 4L, 2L, 3L));

        fixtures.reconciler.poll(10L);

        ArgumentCaptor<ExecutionEvent> event = ArgumentCaptor.forClass(ExecutionEvent.class);
        verify(fixtures.events).save(event.capture());
        assertThat(event.getValue().sequence()).isEqualTo(8);
        assertThat(event.getValue().inputTokens()).isEqualTo(15L);
        assertThat(event.getValue().outputTokens()).isEqualTo(4L);
        assertThat(event.getValue().durationMs()).isEqualTo(1_500L);
        assertThat(event.getValue().failed()).isNull();
        assertThat(fixtures.job.status()).isEqualTo("DONE");
        verify(fixtures.jobs).save(fixtures.job);
    }

    @Test
    @DisplayName("SSE 완료 사건이 있으면 HTTP 결과를 중복 저장하지 않고 작업만 끝낸다")
    void pollDoesNotDuplicateSseCompletion() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        when(fixtures.jobs.findById(10L)).thenReturn(Optional.of(fixtures.job));
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.events.existsByExecutionIdAndHermesSessionIdAndEventType(
                        1L, "child", ExecutionEventType.SUBAGENT_COMPLETED))
                .thenReturn(true);

        fixtures.reconciler.poll(10L);

        verify(fixtures.events, never()).save(any());
        verify(fixtures.jobs).save(fixtures.job);
        assertThat(fixtures.job.status()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("만료 작업은 Hermes를 읽지 않고 EXPIRED로 끝낸다")
    void expiredJobDoesNotCallHermes() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        setField(fixtures.job, "expiresAt", NOW);
        when(fixtures.jobs.findById(10L)).thenReturn(Optional.of(fixtures.job));
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.events.existsByExecutionIdAndHermesSessionIdAndEventType(anyLong(), any(), any()))
                .thenReturn(false);

        fixtures.reconciler.poll(10L);

        verify(fixtures.hermes, never()).readSubagentUsage(any(), any(), any());
        assertThat(fixtures.job.status()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("아직 끝나지 않은 자식은 완료 사건 없이 다음 재조회 시각만 늦춘다")
    void pollRetriesNonterminalChild() throws ReflectiveOperationException {
        Fixtures fixtures = fixtures();
        when(fixtures.jobs.findById(10L)).thenReturn(Optional.of(fixtures.job));
        when(fixtures.executions.lockById(1L)).thenReturn(Optional.of(fixtures.parent));
        when(fixtures.events.existsByExecutionIdAndHermesSessionIdAndEventType(anyLong(), any(), any()))
                .thenReturn(false);
        when(fixtures.hermes.readSubagentUsage(any(), any(), any()))
                .thenReturn(new SubagentSessionUsage(
                        "child", "subagent", "parent", "example-fast", 1.0, null, 10L, 4L, 2L, 3L));

        fixtures.reconciler.poll(10L);

        verify(fixtures.events, never()).save(any());
        assertThat(fixtures.job.status()).isEqualTo("WAITING");
        assertThat(fixtures.job.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    }

    private static SubagentUsageJob job() throws ReflectiveOperationException {
        Instant finishedAt = Instant.parse("2026-10-01T00:00:00Z");
        AgentExecution parent = AgentExecution.builder()
                .userId(1L)
                .agentId(2L)
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .reasoningEffortSource(ReasoningEffortSource.UNKNOWN)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(finishedAt.minusSeconds(1))
                .hermesSessionId("parent")
                .build();
        Field parentId = AgentExecution.class.getDeclaredField("id");
        parentId.setAccessible(true);
        parentId.set(parent, 1L);
        Field finished = AgentExecution.class.getDeclaredField("finishedAt");
        finished.setAccessible(true);
        finished.set(parent, finishedAt);
        ExecutionEvent start = ExecutionEvent.builder()
                .executionId(1L)
                .sequence(1)
                .eventType(ExecutionEventType.SUBAGENT_STARTED)
                .hermesSessionId("child")
                .occurredAt(finishedAt)
                .build();
        return SubagentUsageJob.create(parent, start, "http://runtime", finishedAt);
    }

    private static SubagentUsageJob jobWithId(Long id) throws ReflectiveOperationException {
        SubagentUsageJob job = job();
        setField(job, "id", id);
        return job;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("작업 시작 대기 시간이 지났다");
        }
    }

    private static Fixtures fixtures() throws ReflectiveOperationException {
        AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
        ExecutionEventRepository events = mock(ExecutionEventRepository.class);
        SubagentUsageJobRepository jobs = mock(SubagentUsageJobRepository.class);
        AgentService agents = mock(AgentService.class);
        HermesRunsClient hermes = mock(HermesRunsClient.class);
        ProfileModelDefaultsClient defaults = mock(ProfileModelDefaultsClient.class);
        AgentExecution parent = AgentExecution.builder()
                .userId(1L)
                .agentId(2L)
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .reasoningEffortSource(ReasoningEffortSource.UNKNOWN)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(NOW.minusSeconds(1))
                .hermesSessionId("parent")
                .build();
        setField(parent, "id", 1L);
        setField(parent, "finishedAt", NOW.minusSeconds(1));
        ExecutionEvent start = ExecutionEvent.builder()
                .executionId(1L)
                .sequence(1)
                .eventType(ExecutionEventType.SUBAGENT_STARTED)
                .subagentName("child name")
                .hermesSessionId("child")
                .occurredAt(NOW.minusSeconds(1))
                .build();
        SubagentUsageJob job = SubagentUsageJob.create(parent, start, "http://runtime", NOW.minusSeconds(1));
        setField(job, "id", 10L);
        Agent agent = Agent.of(
                "agent",
                "에이전트",
                "dad",
                "http://runtime",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L);
        setField(agent, "id", 2L);
        SubagentUsageReconciler reconciler = new SubagentUsageReconciler(
                executions,
                events,
                jobs,
                agents,
                hermes,
                defaults,
                new TestTransactionManager(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixtures(reconciler, executions, events, jobs, agents, hermes, defaults, parent, start, job, agent);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixtures(
            SubagentUsageReconciler reconciler,
            AgentExecutionRepository executions,
            ExecutionEventRepository events,
            SubagentUsageJobRepository jobs,
            AgentService agents,
            HermesRunsClient hermes,
            ProfileModelDefaultsClient defaults,
            AgentExecution parent,
            ExecutionEvent start,
            SubagentUsageJob job,
            Agent agent) {}

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
