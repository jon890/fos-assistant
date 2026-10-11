package com.bifos.assistant.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.ProfileModelDefaultsClient;
import com.bifos.assistant.hermes.SubagentProviderClient;
import com.bifos.assistant.hermes.dto.SubagentProviderLookup;
import com.bifos.assistant.hermes.dto.SubagentSessionUsage;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.SamplePriceCatalog;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.SubagentUsageJob;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.usage.infra.SubagentUsageJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/** 제어 시계와 실제 DB 저장을 함께 써 자식의 조회 예약과 완료 기록을 확인한다. */
@BackendIntegrationTest
@SamplePriceCatalog
class SubagentUsageSchedulingTest {
    private static final Instant TICK = Instant.parse("2026-10-01T00:00:35Z");

    @Autowired
    AgentExecutionRepository executions;
    @Autowired
    ExecutionEventRepository events;
    @Autowired
    SubagentUsageJobRepository jobs;
    @Autowired
    AgentRepository agents;
    @Autowired
    AgentService agentService;
    @Autowired
    CostEstimator estimator;
    @Autowired
    PlatformTransactionManager manager;

    private final TestClock clock = new TestClock();
    private HermesRunsClient hermes;
    private SubagentProviderClient providers;
    private SubagentUsageReconciler reconciler;
    private AgentExecution parent;
    private Duration launchDelay;


    @BeforeEach
    void setUp() {
        jobs.deleteAll();
        events.deleteAll();
        executions.deleteAll();
        Agent agent = agents.findByCode("scheduling-test").orElseGet(() -> agents.save(Agent.of(
            "scheduling-test", "시각 검사", "scheduling-test", "http://runtime", CostMode.API,
            CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, 4501L, TICK)));
        Instant finished = TICK.minusSeconds(5).plusMillis(13);
        parent = executions.save(AgentExecution.builder()
            .userId(4501L).agentId(agent.id()).profileName(agent.hermesProfile())
            .hermesSessionId("parent").costMode(CostMode.API).status(ExecutionStatus.SUCCEEDED)
            .timing(finished.minusSeconds(5), finished).build());
        events.save(ExecutionEvent.builder().executionId(parent.id()).sequence(1)
            .eventType(ExecutionEventType.SUBAGENT_STARTED).hermesSessionId("child")
            .occurredAt(finished.minusSeconds(1)).build());
        hermes = mock(HermesRunsClient.class);
        providers = mock(SubagentProviderClient.class);
        BackgroundTasks background = mock(BackgroundTasks.class);
        launchDelay = Duration.ofMillis(3);
        doAnswer(invocation -> {
            clock.advance(launchDelay);
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(background).start(any(), any());
        reconciler = new SubagentUsageReconciler(executions, events, jobs, agentService, hermes,
            mock(ProfileModelDefaultsClient.class), providers, estimator, manager, clock, background);
        when(hermes.readSubagentUsage(any(), any(), any())).thenReturn(usage(null, "anthropic"));
    }


    @ParameterizedTest
    @ValueSource(longs = {300, 700})
    @DisplayName("나노초 반올림 위상과 무관하게 발견 틱과 다음 5초 틱에 조회하고 최종 줄을 한 번 적는다")
    void pollsAtDiscoveryAndNextTick(long nanos) {
        tick(TICK.plusNanos(nanos));
        verify(hermes).readSubagentUsage(any(), any(), any());
        assertThat(job().nextAttemptAt()).isEqualTo(TICK.plusSeconds(5));
        when(hermes.readSubagentUsage(any(), any(), any())).thenReturn(usage(2.5, "anthropic"));

        tick(TICK.plusSeconds(5).plusNanos(nanos));
        tick(TICK.plusSeconds(10));

        verify(hermes, times(2)).readSubagentUsage(any(), any(), any());
        assertThat(job().status()).isEqualTo("DONE");
        assertThat(job().attempts()).isOne();
        assertThat(job().estimatedCostMicros()).isEqualTo(450L);
        assertThat(completions()).hasSize(1);
        assertThat(completions().getFirst().occurredAt()).isEqualTo(job().recordedAt());
        assertThat(job().recordedAt()).isAfter(TICK.plusSeconds(5));
    }


    @Test
    @DisplayName("틱 지연이 15ms에서 7ms로 줄어도 다음 5초 틱을 건너뛰지 않는다")
    void toleratesDecreasingTickJitter() {
        tick(TICK.plusMillis(15));
        tick(TICK.plusSeconds(5).plusMillis(7));
        verify(hermes, times(2)).readSubagentUsage(any(), any(), any());
        assertThat(job().nextAttemptAt()).isEqualTo(TICK.plusSeconds(10));
    }


    @Test
    @DisplayName("5초 넘게 걸린 GET 뒤에는 완료한 초에서 기다리고 실제 기록 시각을 쓴다")
    void waitsAfterSlowRead() {
        when(hermes.readSubagentUsage(any(), any(), any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofMillis(6_200));
            return usage(null, "anthropic");
        });
        tick(TICK);
        assertThat(job().nextAttemptAt()).isEqualTo(TICK.plusSeconds(11));
        when(hermes.readSubagentUsage(any(), any(), any())).thenReturn(usage(2.5, "anthropic"));
        tick(TICK.plusSeconds(10));
        verify(hermes).readSubagentUsage(any(), any(), any());
        tick(TICK.plusSeconds(15));
        assertThat(job().recordedAt()).isEqualTo(TICK.plusSeconds(15).plusMillis(3));
        assertThat(completions()).hasSize(1);
    }


    @Test
    @DisplayName("백그라운드 시작이 밀리면 실제 완료한 초를 재시도 기준으로 쓴다")
    void waitsAfterDelayedLaunch() {
        launchDelay = Duration.ofMillis(8_003);
        tick(TICK);
        assertThat(job().nextAttemptAt()).isEqualTo(TICK.plusSeconds(13));
        launchDelay = Duration.ZERO;
        tick(TICK.plusSeconds(10));
        verify(hermes).readSubagentUsage(any(), any(), any());
        tick(TICK.plusSeconds(15));
        verify(hermes, times(2)).readSubagentUsage(any(), any(), any());
    }


    @Test
    @DisplayName("조회 중 시계가 역행해도 예약은 틱보다 앞서지 않고 완료 사건은 실제 시각이다")
    void keepsScheduleForwardWhenClockMovesBack() {
        when(hermes.readSubagentUsage(any(), any(), any())).thenAnswer(invocation -> {
            clock.set(TICK.minusSeconds(2));
            return usage(null, "anthropic");
        });
        tick(TICK);
        assertThat(job().nextAttemptAt()).isEqualTo(TICK.plusSeconds(5));
        when(hermes.readSubagentUsage(any(), any(), any())).thenAnswer(invocation -> {
            clock.set(TICK.plusSeconds(4));
            return usage(2.5, "anthropic");
        });
        tick(TICK.plusSeconds(5));
        assertThat(completions().getFirst().occurredAt()).isEqualTo(TICK.plusSeconds(4));
    }


    @Test
    @DisplayName("provider 미확인도 다음 틱에서 다시 읽고 확인한 뒤 한 번만 기록한다")
    void retriesProviderWithoutDuplicatingCompletion() {
        when(hermes.readSubagentUsage(any(), any(), any()))
            .thenReturn(usage((double)TICK.getEpochSecond(), null));
        when(providers.read(any(), any())).thenReturn(SubagentProviderLookup.unreachable());
        tick(TICK.plusNanos(700));
        assertThat(job().recordedAt()).isNull();
        assertThat(completions()).isEmpty();
        when(providers.read(any(), any()))
            .thenReturn(SubagentProviderLookup.found("anthropic", "example-model-large"));
        tick(TICK.plusSeconds(5).plusMillis(7));
        tick(TICK.plusSeconds(10));
        assertThat(job().provider()).isEqualTo("anthropic");
        assertThat(job().estimatedCostMicros()).isEqualTo(450L);
        assertThat(completions()).hasSize(1);
        verify(hermes, times(2)).readSubagentUsage(any(), any(), any());
    }


    @Test
    @DisplayName("직접 조회는 실제 시각에 간격을 더하고 재기동 뒤 다음 가능한 틱에서 이어 간다")
    void preservesDirectPollingAndPersistedSchedule() {
        reconciler.discover(TICK);
        clock.set(TICK.plusMillis(15));
        reconciler.poll(job().id());
        assertThat(job().nextAttemptAt()).isEqualTo(TICK.plusSeconds(5).plusMillis(15));
        tick(TICK.plusSeconds(10));
        verify(hermes, times(2)).readSubagentUsage(any(), any(), any());
        assertThat(job().attempts()).isEqualTo(2);
        assertThat(job().expiresAt()).isEqualTo(parent.finishedAt().plus(Duration.ofHours(24)));
    }


    @Test
    @DisplayName("초 단위 예약과 달라도 backoff 진입은 실제 경과 2분으로 판단한다")
    void usesActualTimeForBackoff() {
        tick(TICK.plusNanos(700));
        launchDelay = Duration.ZERO;
        tick(TICK.plusSeconds(120).plusNanos(100));
        assertThat(job().backoffAttempts()).isZero();
        tick(TICK.plusSeconds(125));
        assertThat(job().backoffAttempts()).isOne();
        assertThat(job().nextAttemptAt()).isEqualTo(TICK.plusSeconds(135));
    }


    @Test
    @DisplayName("1초 cron 도 예약 전에 반복하지 않고 긴 cron 은 다음 가능한 틱에서 조회한다")
    void waitsForDueTimeUnderDifferentTickIntervals() {
        tick(TICK);
        for (int second = 1; second < 5; second++) {
            tick(TICK.plusSeconds(second));
        }
        verify(hermes).readSubagentUsage(any(), any(), any());
        tick(TICK.plusSeconds(5));
        tick(TICK.plusSeconds(15));
        verify(hermes, times(3)).readSubagentUsage(any(), any(), any());
    }


    @Test
    @DisplayName("느린 GET 중 기한을 넘기면 실제 종료 시각에서 만료시키고 기한을 늘리지 않는다")
    void expiresWhenSlowReadCrossesDeadline() {
        reconciler.discover(TICK);
        Instant deadline = job().expiresAt();
        when(hermes.readSubagentUsage(any(), any(), any())).thenAnswer(invocation -> {
            clock.set(deadline.plusMillis(1));
            return usage(null, "anthropic");
        });
        tick(deadline.minusMillis(100));
        assertThat(job().status()).isEqualTo("EXPIRED");
        assertThat(job().expiresAt()).isEqualTo(deadline);
        assertThat(job().attempts()).isZero();
        assertThat(completions()).isEmpty();
    }


    @Test
    @DisplayName("백그라운드 대기 중 기한을 넘기면 session 을 조회하지 않는다")
    void expiresBeforeDelayedReadStarts() {
        reconciler.discover(TICK);
        Instant deadline = job().expiresAt();
        launchDelay = Duration.ofSeconds(2);
        tick(deadline.minusSeconds(1));
        assertThat(job().status()).isEqualTo("EXPIRED");
        verify(hermes, never()).readSubagentUsage(any(), any(), any());
    }


    @Test
    @DisplayName("느린 provider 조회가 10분 창을 넘기면 실제 종료 시각으로 포기하고 한 번 기록한다")
    void closesProviderWindowAfterSlowLookup() {
        double endedAt = TICK.getEpochSecond() - 599;
        when(hermes.readSubagentUsage(any(), any(), any())).thenReturn(usage(endedAt, null));
        when(providers.read(any(), any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(2));
            return SubagentProviderLookup.unreachable();
        });
        tick(TICK);
        assertThat(job().status()).isEqualTo("DONE");
        assertThat(job().unconfirmedReason()).isEqualTo("PROVIDER_UNKNOWN");
        assertThat(job().estimatedCostMicros()).isNull();
        assertThat(job().recordedAt()).isEqualTo(TICK.plusSeconds(2).plusMillis(3));
        assertThat(completions()).hasSize(1);
    }


    @Test
    @DisplayName("provider 를 기다리는 중에도 실제 작업 기한이 지나면 만료된다")
    void expiresProviderWaitAtActualDeadline() {
        reconciler.discover(TICK);
        Instant deadline = job().expiresAt();
        when(hermes.readSubagentUsage(any(), any(), any()))
            .thenReturn(usage((double)deadline.minusSeconds(1).getEpochSecond(), null));
        when(providers.read(any(), any())).thenAnswer(invocation -> {
            clock.set(deadline.plusMillis(1));
            return SubagentProviderLookup.unreachable();
        });
        tick(deadline.minusMillis(100));
        assertThat(job().status()).isEqualTo("EXPIRED");
        assertThat(job().attempts()).isZero();
        assertThat(completions()).isEmpty();
    }


    private void tick(Instant now) {
        clock.set(now);
        reconciler.reconcile();
    }


    private SubagentUsageJob job() {
        assertThat(jobs.findAll()).hasSize(1);
        return jobs.findAll().getFirst();
    }


    private List<ExecutionEvent> completions() {
        return events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(parent.id())).stream()
            .filter(event -> event.eventType() == ExecutionEventType.SUBAGENT_COMPLETED).toList();
    }


    private static SubagentSessionUsage usage(Double endedAt, String provider) {
        return new SubagentSessionUsage("child", "subagent", "parent", "example-model-large", provider,
            1.0, endedAt, 10L, 4L, 0L, 0L);
    }
}
