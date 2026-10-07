package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.orchestration.application.ChildExecutionRunner;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.orchestration.application.DelegationResult;
import com.bifos.assistant.orchestration.domain.ChildResult;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.concurrent.VirtualThreadBackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 위임 시작이 데이터베이스와 겹치는 자리를 대역으로 고정한다(ADR-017).
 *
 * <p>잠금 대기와 유일 제약 경합은 실제 데이터베이스로는 순서를 정할 수 없어 흔들린다. 저장소와 실행을 대역으로 바꿔
 * 어느 요청이 어디서 멈추는지를 정해 둔다.
 */
class AgentDelegationServiceRaceTest {

    private static final Duration SUBMIT_TIMEOUT = Duration.ofMillis(200);
    private static final long ROOT_ID = 10L;
    private static final long CONVERSATION_ID = 20L;
    private static final String WORKER = "race-worker";

    private final AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
    private final ChildExecutionRunner children = mock(ChildExecutionRunner.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final CurrentUser user = new CurrentUser(1L, "race-a@example.com", "가", 1L, UserRole.MEMBER);
    private final AgentExecution origin = mock(AgentExecution.class);
    private final ProactiveCheckGuard checkGuard = mock(ProactiveCheckGuard.class);
    /** 서비스가 위임마다 읽는 설정이다. 서버 전체 한도를 바꾸는 검사만 새 값을 넣는다. */
    private final AtomicReference<DelegationProperties> settings = new AtomicReference<>(withMaxActive(1));

    private AgentDelegationService delegations;

    @BeforeEach
    void setUp() {
        when(origin.id()).thenReturn(ROOT_ID);
        when(origin.treeRootId()).thenReturn(ROOT_ID);
        when(origin.conversationId()).thenReturn(CONVERSATION_ID);
        when(conversations.findByIdAndUserIdAndDeletedAtIsNull(CONVERSATION_ID, user.id()))
                .thenReturn(Optional.of(mock(Conversation.class)));
        Agent agent = mock(Agent.class);
        when(agent.apiBaseUrl()).thenReturn("http://agent-runtime.test/p/" + WORKER);
        when(agent.hermesProfile()).thenReturn(WORKER);
        when(children.startableAgent(user, WORKER)).thenReturn(agent);
        // 보통 turn 의 위임을 본다. 살펴보기 트리를 보는 검사만 참으로 바꾼다.
        when(checkGuard.isCheckTree(any())).thenReturn(false);
        delegations = service(new VirtualThreadBackgroundTasks());
    }

    /** 서버 전체 한도를 1 로 둬, 거절한 요청이 자리를 돌려주지 않고 남기면 다음 요청이 BUSY 가 된다. */
    private AgentDelegationService service(BackgroundTasks backgroundTasks) {
        return new AgentDelegationService(
                mock(AgentService.class),
                executions,
                mock(ExecutionDeliveryWriter.class),
                children,
                conversations,
                new LiveProperties<>() {
                    @Override
                    public DelegationProperties current() {
                        return settings.get();
                    }

                    @Override
                    public Class<DelegationProperties> type() {
                        return DelegationProperties.class;
                    }
                },
                mock(TurnCancellation.class),
                mock(HermesRunsClient.class),
                event -> {},
                checkGuard,
                Clock.systemUTC(),
                backgroundTasks);
    }

    @Test
    @DisplayName("루트 잠금을 제한 시간 안에 잡지 못하면 실행을 시작하지 않고 SUBMIT FAILED 다")
    void doesNotStartRunAndSubmitFailedWhenRootLockNotAcquiredInTime() throws Exception {
        DelegationKey holding = key("call_holding");
        DelegationKey waiting = key("call_waiting");
        CountDownLatch holdingEntered = new CountDownLatch(1);
        CountDownLatch releaseHolding = new CountDownLatch(1);
        // 앞 요청이 잠금 안의 같은 호출 확인에서 멈춘다. 데이터베이스가 느린 것과 같다.
        when(executions.findByDelegationKey(holding.value())).thenAnswer(invocation -> {
            holdingEntered.countDown();
            releaseHolding.await(10, TimeUnit.SECONDS);
            return Optional.empty();
        });

        DelegationResult holdingResult;
        DelegationResult waitingResult;
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<DelegationResult> first =
                    pool.submit(() -> delegations.delegate(user, origin, holding, WORKER, "앞 요청"));
            assertThat(holdingEntered.await(10, TimeUnit.SECONDS))
                    .as("앞 요청이 잠금을 쥐었다")
                    .isTrue();

            waitingResult = delegations.delegate(user, origin, waiting, WORKER, "잠금을 기다리는 요청");

            releaseHolding.countDown();
            holdingResult = first.get(10, TimeUnit.SECONDS);
        }

        assertThat(waitingResult.failure())
                .as("잠금을 못 잡은 요청: %s", waitingResult)
                .isEqualTo(DelegationResult.Failure.SUBMIT_FAILED);
        assertThat(holdingResult.failure())
                .as("잠금 안에서 제한 시간이 지난 요청: %s", holdingResult)
                .isEqualTo(DelegationResult.Failure.SUBMIT_FAILED);
        verify(children, never()).delegate(any(), any(), any(), any(), any(), any(), any(), any(), any());

        startsWithRow(77L);
        DelegationResult retried = delegations.delegate(user, origin, waiting, WORKER, "같은 키로 다시 부른다");
        assertThat(retried.executionId())
                .as("거절한 요청이 줄과 자리를 남기지 않아 같은 키로 새로 시작한다: %s", retried)
                .isEqualTo(77L);
        assertThat(retried.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("실행 스레드를 띄우지 못하면 SUBMIT FAILED 로 끝나고 동시 위임 자리를 돌려준다")
    void returnsSlotAndSubmitFailedWhenRunThreadCannotStart() {
        BackgroundTasks real = new VirtualThreadBackgroundTasks();
        AtomicBoolean failNext = new AtomicBoolean(true);
        // 첫 시작만 실패시키고 그 뒤는 실제로 띄운다. 스레드를 더 띄울 수 없는 상황과 같다.
        BackgroundTasks failingOnce = new BackgroundTasks() {
            @Override
            public Thread start(String name, Runnable task) {
                if (failNext.compareAndSet(true, false)) {
                    throw new IllegalStateException("스레드를 띄우지 못했다");
                }
                return real.start(name, task);
            }

            @Override
            public Thread unstarted(String name, Runnable task) {
                return real.unstarted(name, task);
            }
        };
        delegations = service(failingOnce);

        DelegationResult failed = delegations.delegate(user, origin, key("call_start_failed"), WORKER, "시작이 실패한다");

        assertThat(failed.failure()).as("시작이 실패한 요청: %s", failed).isEqualTo(DelegationResult.Failure.SUBMIT_FAILED);
        verify(children, never()).delegate(any(), any(), any(), any(), any(), any(), any(), any(), any());

        startsWithRow(78L);
        DelegationResult next = delegations.delegate(user, origin, key("call_after_failure"), WORKER, "다음 요청");
        assertThat(next.failure())
                .as("서버 한도가 1 이라 자리를 돌려주지 않았으면 BUSY 다: %s", next)
                .isNull();
        assertThat(next.executionId()).as("다음 요청: %s", next).isEqualTo(78L);
        assertThat(next.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("실행 줄 저장이 유일 제약에 걸리면 먼저 저장된 같은 키의 줄을 다시 읽어 그 번호를 준다")
    void rereadsRowOfSameKeySavedFirstAndReturnsItsIdOnUniqueConflict() {
        DelegationKey raced = key("call_raced");
        AgentExecution saved = mock(AgentExecution.class);
        when(saved.id()).thenReturn(55L);
        when(saved.userId()).thenReturn(user.id());
        when(saved.status()).thenReturn(ExecutionStatus.RUNNING);
        // 잠금 안에서는 아직 없고, 저장이 걸린 뒤 다시 읽으면 다른 요청이 저장한 줄이 있다.
        when(executions.findByDelegationKey(raced.value()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(saved));
        when(children.delegate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("같은 delegation_key"));

        DelegationResult result = delegations.delegate(user, origin, raced, WORKER, "같은 호출");

        assertThat(result.accepted()).as("결과: %s", result).isTrue();
        assertThat(result.executionId()).isEqualTo(55L);
        assertThat(result.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("다시 읽은 같은 키의 줄이 다른 사용자의 것이면 번호를 알리지 않는다")
    void doesNotRevealIdWhenRereadRowOfSameKeyBelongsToOtherUser() {
        DelegationKey raced = key("call_other_user");
        AgentExecution saved = mock(AgentExecution.class);
        when(saved.id()).thenReturn(56L);
        when(saved.userId()).thenReturn(user.id() + 1);
        when(saved.status()).thenReturn(ExecutionStatus.RUNNING);
        when(executions.findByDelegationKey(raced.value()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(saved));
        when(children.delegate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("같은 delegation_key"));

        DelegationResult result = delegations.delegate(user, origin, raced, WORKER, "같은 호출");

        assertThat(result.failure()).as("결과: %s", result).isEqualTo(DelegationResult.Failure.SUBMIT_FAILED);
        assertThat(result.executionId()).isNull();
    }

    @Test
    @DisplayName("살펴보기 트리에서 RUNNING 으로 읽은 실행이 도는 표시를 찾기 전에 끝났으면 한 번 다시 읽어 끝난 상태를 준다")
    void rereadsEndedRowWhenRunEndsBeforeRunningLookup() {
        when(checkGuard.isCheckTree(origin)).thenReturn(true);
        AgentExecution runningRow = delegatedRow(88L, ExecutionStatus.RUNNING);
        AgentExecution endedRow = delegatedRow(88L, ExecutionStatus.SUCCEEDED);
        // 처음 읽을 때는 돌고, 도는 표시를 찾을 때는 이미 끝나 표시가 없다.
        when(executions.findById(88L)).thenReturn(Optional.of(runningRow)).thenReturn(Optional.of(endedRow));

        Optional<AgentExecution> read = delegations.status(user, origin, 88L, Duration.ofSeconds(5));

        assertThat(read).as("다시 읽은 줄").containsSame(endedRow);
    }

    @Test
    @DisplayName("서버 전체 동시 위임 한도는 위임마다 지금 설정을 읽고 끝난 실행이 돌려준 자리는 다시 얻는다")
    void serverWideLimitReadsCurrentSettingsAndReusesReturnedSlot() throws Exception {
        List<Thread> started = new CopyOnWriteArrayList<>();
        BackgroundTasks real = new VirtualThreadBackgroundTasks();
        // 띄운 실행 스레드를 모아, 자리를 돌려줄 때까지 기다린다.
        BackgroundTasks recording = new BackgroundTasks() {
            @Override
            public Thread start(String name, Runnable task) {
                Thread thread = real.start(name, task);
                started.add(thread);
                return thread;
            }

            @Override
            public Thread unstarted(String name, Runnable task) {
                return real.unstarted(name, task);
            }
        };
        delegations = service(recording);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicLong nextId = new AtomicLong(100L);
        // 실행이 줄을 만들고 제출한 뒤 finish 까지 돌아, 그동안 자리를 쥔다.
        when(children.delegate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    long id = nextId.incrementAndGet();
                    AgentExecution row = mock(AgentExecution.class);
                    when(row.id()).thenReturn(id);
                    Consumer<AgentExecution> onStarted = invocation.getArgument(6);
                    BiConsumer<AgentExecution, String> onSubmitted = invocation.getArgument(7);
                    onStarted.accept(row);
                    onSubmitted.accept(row, "run-" + id);
                    finish.await(10, TimeUnit.SECONDS);
                    return ChildResult.succeeded(id, "답");
                });

        DelegationResult first = delegations.delegate(user, origin, key("call_limit_first"), WORKER, "첫 요청");
        DelegationResult refused = delegations.delegate(user, origin, key("call_limit_second"), WORKER, "한도 1 의 둘째");

        settings.set(withMaxActive(2));
        DelegationResult second = delegations.delegate(user, origin, key("call_limit_third"), WORKER, "한도 2 의 둘째");

        finish.countDown();
        for (Thread thread : started) {
            assertThat(thread.join(Duration.ofSeconds(10)))
                    .as("실행 스레드 %s 가 끝났다", thread.getName())
                    .isTrue();
        }
        settings.set(withMaxActive(1));
        DelegationResult afterReturn = delegations.delegate(user, origin, key("call_limit_after"), WORKER, "돌려준 뒤");

        assertThat(first.failure()).as("한도 1 의 첫 요청: %s", first).isNull();
        assertThat(refused.failure()).as("한도 1 에서 자리를 쥔 동안의 둘째: %s", refused).isEqualTo(DelegationResult.Failure.BUSY);
        assertThat(second.failure()).as("한도를 2 로 올린 뒤의 둘째: %s", second).isNull();
        assertThat(afterReturn.failure())
                .as("두 실행이 자리를 돌려준 뒤 한도 1 의 요청: %s", afterReturn)
                .isNull();
    }

    /** 서버 전체 한도만 다른 위임 설정이다. 나머지는 {@link #service} 의 기본값과 같다. */
    private static DelegationProperties withMaxActive(int maxActive) {
        return new DelegationProperties(2, 4, maxActive, SUBMIT_TIMEOUT, 100, Duration.ofSeconds(20));
    }

    /** 이 대화에서 요청자가 맡긴 위임 실행 줄이다. */
    private AgentExecution delegatedRow(Long id, ExecutionStatus status) {
        AgentExecution row = mock(AgentExecution.class);
        when(row.id()).thenReturn(id);
        when(row.userId()).thenReturn(user.id());
        when(row.conversationId()).thenReturn(CONVERSATION_ID);
        when(row.delegationKey()).thenReturn("race-key-" + id);
        when(row.status()).thenReturn(status);
        return row;
    }

    /** 다음 실행이 줄을 만들고 곧바로 제출하게 한다. */
    private void startsWithRow(Long executionId) {
        AgentExecution row = mock(AgentExecution.class);
        when(row.id()).thenReturn(executionId);
        when(children.delegate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<AgentExecution> onStarted = invocation.getArgument(6);
                    BiConsumer<AgentExecution, String> onSubmitted = invocation.getArgument(7);
                    onStarted.accept(row);
                    onSubmitted.accept(row, "run-" + executionId);
                    return ChildResult.succeeded(executionId, "답");
                });
    }

    private static DelegationKey key(String toolCallId) {
        return DelegationKey.of("race-chief", "fos-root", "fos-root", toolCallId);
    }
}
