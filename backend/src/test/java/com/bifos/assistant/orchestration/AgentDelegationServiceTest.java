package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.orchestration.application.DelegationResult;
import com.bifos.assistant.orchestration.application.DelegationStop;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 위임 시작의 제출 대기, 서버 전체 한도, 답 자르기, 같은 호출의 동시 요청, 중지를 고정한다(ADR-017).
 *
 * <p>설정값을 짧고 작게 바꿔 띄운다. 도구 경계와 요청자 판정은 {@code McpAgentToolsTest} 가 본다. 위임은 가상 스레드에서
 * 돌므로 각 검사는 자기가 띄운 실행이 끝날 때까지 기다린 뒤 끝난다.
 */
@SpringBootTest(
        properties = {
            "assistant.delegation.submit-timeout=300ms",
            "assistant.delegation.max-active=2",
            "assistant.delegation.output-max-chars=20"
        })
@ActiveProfiles("test")
@Import(AgentDelegationServiceTest.StubRuntime.class)
class AgentDelegationServiceTest {

    private static final String CHIEF_PROFILE = "delegation-chief";
    private static final String WORKER = "delegation-worker";
    private static final String EMAIL = "delegation-a@example.com";
    private static final String OTHER_EMAIL = "delegation-b@example.com";
    private static final Duration SUBMIT_TIMEOUT = Duration.ofMillis(300);
    private static final int MAX_ACTIVE = 2;

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired
    AgentDelegationService delegations;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    TurnCancellation turns;

    @Autowired
    ChatService chat;

    private CurrentUser user;
    private AgentExecution origin;
    private String root;
    private Conversation conversation;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    /** 이 검사의 profile 과 에이전트와 사용자만 지운다. 같은 H2 를 다른 검사 클래스와 함께 쓴다. */
    @BeforeEach
    void setUp() {
        stub().reset();
        for (String profile : List.of(CHIEF_PROFILE, WORKER)) {
            jdbc.update("DELETE FROM agent_execution WHERE profile_name = ?", profile);
        }
        agents.findByCode(WORKER).ifPresent(agents::delete);
        users.findByEmail(EMAIL).ifPresent(users::delete);
        users.findByEmail(OTHER_EMAIL).ifPresent(users::delete);
        AppUser saved = users.save(AppUser.of(EMAIL, "가", 1L, UserRole.MEMBER, Instant.now()));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
        Agent worker = agents.save(Agent.of(
                WORKER,
                "조사원",
                WORKER,
                "http://agent-runtime.test/p/" + WORKER,
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.PRIVATE,
                saved.id(),
                Instant.now()));
        conversation = conversations.save(Conversation.startedBy(saved.id(), "맡기기", worker.id(), Instant.now()));
        root = "fos-" + UUID.randomUUID();
        origin = turn(root);
    }

    @Test
    @DisplayName("제출을 붙잡으면 제한 시간 뒤 번호와 RUNNING 이 오고 풀면 결과가 그 줄에 적힌다")
    void heldSubmitReturnsIdAndRunningAfterTimeoutThenRecordsResult() throws Exception {
        stub().willAnswer(command -> completed(command, "붙잡혔던 답"));
        stub().holdSubmits();

        long before = System.nanoTime();
        DelegationResult result = delegate("붙잡힌다");
        Duration waited = Duration.ofNanos(System.nanoTime() - before);

        assertThat(result.accepted()).as("결과: %s", result).isTrue();
        assertThat(result.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(waited).as("제출을 제한 시간까지 기다린다").isGreaterThanOrEqualTo(SUBMIT_TIMEOUT.minusMillis(20));
        AgentExecution held = executions.findById(result.executionId()).orElseThrow();
        assertThat(held.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(held.hermesRunId()).as("아직 제출하지 않았다").isNull();

        stub().releaseSubmits();

        AgentExecution finished = awaitFinished(result.executionId());
        assertThat(finished.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(finished.outputText()).isEqualTo("붙잡혔던 답");
        assertThat(finished.hermesRunId()).isNotNull();
    }

    @Test
    @DisplayName("답이 상한을 넘으면 잘리고 잘렸다는 한 줄이 붙는다")
    void truncatesAnswerOverLimitAndAppendsTruncationLine() throws Exception {
        stub().willAnswer(
                        command -> completed(command, command.input().equals("길다") ? "가".repeat(21) : "나".repeat(20)));

        DelegationResult longer = delegate("길다");
        DelegationResult exact = delegate("딱 맞다");

        assertThat(awaitFinished(longer.executionId()).outputText())
                .isEqualTo("가".repeat(20) + "\n\n[답이 20자를 넘어 뒷부분을 잘랐다]");
        AgentExecution exactRow = awaitFinished(exact.executionId());
        assertThat(exactRow.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(exactRow.outputText()).as("상한과 같은 길이는 그대로 둔다").isEqualTo("나".repeat(20));
    }

    @Test
    @DisplayName("서버 전체 한도를 채우면 BUSY 이고 끝나면 자리가 돌아온다")
    void isBusyAtServerWideLimitAndSlotReturnsWhenFinished() throws Exception {
        stub().willAnswer(command -> completed(command, "답"));
        stub().holdSubmits();
        List<Long> started = new ArrayList<>();
        for (int i = 0; i < MAX_ACTIVE; i++) {
            // 앞 검사의 실행 스레드가 자리를 막 돌려주는 중일 수 있어 BUSY 이면 잠시 뒤 다시 부른다.
            started.add(acceptedWithin(Duration.ofSeconds(5), "붙잡힌 일 " + i).executionId());
        }

        DelegationResult busy = delegate("넘친다");

        assertThat(busy.accepted()).isFalse();
        assertThat(busy.failure()).isEqualTo(DelegationResult.Failure.BUSY);
        assertThat(busy.executionId()).isNull();
        stub().releaseSubmits();
        for (Long id : started) {
            assertThat(awaitFinished(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        }
        DelegationResult afterwards = acceptedWithin(Duration.ofSeconds(5), "끝난 뒤");
        assertThat(awaitFinished(afterwards.executionId()).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("같은 키로 동시에 두 번 부르면 잠금을 기다린 요청이 먼저 생긴 줄을 읽어 실행이 하나이고 같은 번호가 온다")
    void concurrentCallsWithSameKeyReadEarlierRowAndReturnSameId() throws Exception {
        stub().willAnswer(command -> completed(command, "답"));
        stub().holdSubmits();
        DelegationKey key = DelegationKey.of(CHIEF_PROFILE, root, root, "call_" + UUID.randomUUID());
        Callable<DelegationResult> call = () -> delegations.delegate(user, origin, key, WORKER, "같은 호출");

        List<DelegationResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<DelegationResult>> futures = pool.invokeAll(List.of(call, call));
            for (Future<DelegationResult> future : futures) {
                results.add(future.get());
            }
        }
        stub().releaseSubmits();

        assertThat(results).allMatch(DelegationResult::accepted, "둘 다 받아들인다");
        assertThat(results.get(1).executionId()).isEqualTo(results.get(0).executionId());
        assertThat(executions.findByRootExecutionId(origin.id())).hasSize(1);
        awaitFinished(results.get(0).executionId());
        assertThat(stub().received()).hasSize(1);
    }

    @Test
    @DisplayName("도는 위임 실행을 멈추면 Hermes 에 중지가 가고 멈춘 자리까지의 답과 함께 CANCELLED 로 남는다")
    void stoppingRunningDelegationSendsStopToHermesAndLeavesCancelled() throws Exception {
        stub().willAnswer(command -> completed(command, "멈춘 자리까지의 답"));
        holdUntilStopped();
        DelegationResult started = delegate("멈출 일");
        String runId = executions.findById(started.executionId()).orElseThrow().hermesRunId();
        assertThat(runId).as("제출까지 기다렸다").isNotNull();

        Optional<DelegationStop> stopped = delegations.stop(user, origin, started.executionId());

        assertThat(stopped).as("멈춘 뒤의 상태").isPresent();
        assertThat(stopped.get().execution().status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(stopped.get().stopRequested()).as("중지 표시를 켰다").isTrue();
        assertThat(stub().stopped()).containsExactly(runId);
        AgentExecution finished = awaitFinished(started.executionId());
        assertThat(finished.status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(finished.outputText()).isEqualTo("멈춘 자리까지의 답");
    }

    @Test
    @DisplayName("멈춘 실행이 받은 답이 없으면 답을 비워 둔다")
    void leavesAnswerEmptyWhenStoppedRunHasNoAnswer() throws Exception {
        stub().willAnswer(command -> completed(command, ""));
        holdUntilStopped();
        DelegationResult started = delegate("답이 없다");

        assertThat(delegations
                        .stop(user, origin, started.executionId())
                        .orElseThrow()
                        .execution()
                        .status())
                .isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(awaitFinished(started.executionId()).outputText()).isNull();
    }

    @Test
    @DisplayName("run 번호가 붙기 전에 온 중지는 번호가 붙는 자리에서 Hermes 에 보낸다")
    void stopBeforeRunIdIsAttachedIsSentToHermesWhenIdGetsAttached() throws Exception {
        stub().willAnswer(command -> completed(command, "답"));
        stub().holdSubmits();
        DelegationResult started = delegate("제출 전에 멈춘다");
        assertThat(executions.findById(started.executionId()).orElseThrow().hermesRunId())
                .as("아직 제출하지 않았다")
                .isNull();

        Optional<DelegationStop> stopped;
        try (ExecutorService releaser = Executors.newSingleThreadExecutor()) {
            releaser.submit(() -> {
                Thread.sleep(300);
                stub().releaseSubmits();
                return null;
            });
            stopped = delegations.stop(user, origin, started.executionId());
        }

        AgentExecution finished = awaitFinished(started.executionId());
        assertThat(stopped.orElseThrow().execution().status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(finished.status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(stub().stopped()).containsExactly(finished.hermesRunId());
    }

    @Test
    @DisplayName("끝난 실행을 멈추면 Hermes 에 보내지 않고 끝난 상태를 그대로 준다")
    void stoppingFinishedRunSendsNothingToHermesAndReturnsEndedState() throws Exception {
        stub().willAnswer(command -> completed(command, "끝난 답"));
        DelegationResult started = delegate("먼저 끝난다");
        awaitFinished(started.executionId());

        DelegationStop stopped =
                delegations.stop(user, origin, started.executionId()).orElseThrow();

        assertThat(stopped.execution().status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(stopped.execution().outputText()).isEqualTo("끝난 답");
        assertThat(stopped.stopRequested()).as("끝난 실행은 멈추지 않는다").isFalse();
        assertThat(stub().stopped()).isEmpty();
    }

    @Test
    @DisplayName("끊긴 위임 실행의 에이전트 행이 없으면 Hermes 에 보내지 않고 중지를 요청하지 않은 상태로 답한다")
    void answersNotStopRequestedWithoutSendingWhenCutRunHasNoAgentRow() {
        // 서버가 다시 떠 이 프로세스에 중지 표시가 없는 도는 위임 실행이다. 가리키는 에이전트 행은 없다.
        AgentExecution detached = executions.save(AgentExecution.builder()
                .userId(user.id())
                .conversationId(conversation.id())
                .agentId(999_999_999L)
                .parentExecutionId(origin.id())
                .rootExecutionId(origin.id())
                .profileName(WORKER)
                .hermesRunId("run-detached")
                .delegationKey("detached-" + UUID.randomUUID())
                .costMode(CostMode.API)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.parse("2026-09-30T00:00:00Z"))
                .build());

        DelegationStop stopped = delegations.stop(user, origin, detached.id()).orElseThrow();

        assertThat(stopped.stopRequested()).as("보낼 주소가 없어 멈추지 못했다").isFalse();
        assertThat(stopped.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(stub().stopped()).isEmpty();
    }

    @Test
    @DisplayName("같은 대화의 다음 turn 에서 앞 turn 이 맡긴 실행을 멈춘다")
    void stopsRunDelegatedByEarlierTurnInNextTurnOfSameConversation() throws Exception {
        stub().willAnswer(command -> completed(command, "답"));
        holdUntilStopped();
        DelegationResult started = delegate("앞 turn 의 일");
        AgentExecution nextTurn = turn("fos-" + UUID.randomUUID());
        assertThat(nextTurn.treeRootId()).as("다음 turn 은 루트가 다르다").isNotEqualTo(origin.treeRootId());

        AgentExecution stopped = delegations
                .stop(user, nextTurn, started.executionId())
                .orElseThrow()
                .execution();

        assertThat(stopped.status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(stub().stopped()).containsExactly(stopped.hermesRunId());
        awaitFinished(started.executionId());
    }

    @Test
    @DisplayName("사용자가 루트 turn 을 멈추면 그 turn 이 도는 동안 맡긴 자식도 멈춘다")
    void stoppingRootTurnAlsoStopsChildrenDelegatedWhileItRuns() throws Exception {
        stub().willAnswer(command -> completed(command, "답"));
        // 대역 Hermes 는 중지를 받은 뒤에도 completed 를 준다. 루트 turn 의 중지가 확정된 뒤에 답하게 해, 확정된 중지를
        // 보고 CANCELLED 로 적는지 본다.
        CountDownLatch awaiting = holdUntilRootStopConfirmed();
        TurnCancellation.TurnHandle handle = turns.open(user.id(), conversation.id());
        try {
            turns.rekey(handle, origin.id());
            DelegationResult started = delegate("turn 이 도는 동안 맡긴 일");
            assertThat(awaiting.await(10, TimeUnit.SECONDS))
                    .as("자식이 완료 대기에 들어섰다")
                    .isTrue();
            String runId =
                    executions.findById(started.executionId()).orElseThrow().hermesRunId();
            assertThat(turns.pendingStops(handle))
                    .as("turn 에 자식 run 이 붙었다")
                    .extracting(TurnCancellation.RunRef::getRunId)
                    .containsExactly(runId);

            chat.stop(user, origin.id());

            AgentExecution child = awaitFinished(started.executionId());
            assertThat(child.status()).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(stub().stopped()).containsExactly(runId);
        } finally {
            turns.close(handle);
        }
    }

    @Test
    @DisplayName("루트 turn 의 중지를 Hermes 가 받지 않은 사이에 끝난 자식은 성공으로 남는다")
    void childFinishedBeforeHermesAcceptedRootStopStaysSucceeded() throws Exception {
        stub().willAnswer(command -> completed(command, "멀쩡한 답"));
        CountDownLatch stopAttempted = new CountDownLatch(1);
        AtomicReference<Long> childId = new AtomicReference<>();
        AtomicReference<AgentExecution> finishedWhileCancelled = new AtomicReference<>();
        CountDownLatch awaiting = new CountDownLatch(1);
        stub().beforeAwait(() -> {
            awaiting.countDown();
            await(stopAttempted);
        });
        TurnCancellation.TurnHandle handle = turns.open(user.id(), conversation.id());
        try {
            turns.rekey(handle, origin.id());
            DelegationResult started = delegate("중지가 실패하는 turn 의 일");
            childId.set(started.executionId());
            // 자식 run 이 turn 에 붙은 뒤에 멈춘다. 그래야 중지를 보내는 쪽이 이 검사 스레드 하나다.
            assertThat(awaiting.await(10, TimeUnit.SECONDS))
                    .as("자식이 완료 대기에 들어섰다")
                    .isTrue();
            // 중지 버튼을 눌러 turn 에 취소 표시가 켜진 채로, Hermes 가 중지를 받지 않는 자리에서 자식을 끝낸다.
            stub().onStop(runId -> {
                stopAttempted.countDown();
                try {
                    finishedWhileCancelled.set(awaitFinished(childId.get()));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Hermes 가 중지를 받지 않았다");
            });

            try {
                chat.stop(user, origin.id());
            } catch (ApiException ex) {
                // 자식 run 이 목록에서 먼저 빠졌는지에 따라 중지 응답이 갈린다. 여기서 보는 것은 자식의 상태다.
                assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
            }

            assertThat(finishedWhileCancelled.get())
                    .as("turn 에 취소 표시가 켜진 사이에 끝났다")
                    .isNotNull();
            assertThat(finishedWhileCancelled.get().status())
                    .as("확정되지 않은 중지로 멈추지 않는다")
                    .isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(finishedWhileCancelled.get().outputText()).isEqualTo("멀쩡한 답");
        } finally {
            turns.close(handle);
        }
    }

    @Test
    @DisplayName("끝난 turn 과 같은 사용자의 다른 대화 turn 에는 자식 run 을 붙이지 않는다")
    void doesNotAttachChildRunToEndedTurnOrOtherConversationTurnOfSameUser() throws Exception {
        stub().willAnswer(command -> completed(command, "답"));
        CountDownLatch awaiting = holdUntilStopped();
        TurnCancellation.TurnHandle ended = turns.open(user.id(), conversation.id());
        turns.rekey(ended, origin.id());
        turns.close(ended);
        Conversation otherConversation =
                conversations.save(Conversation.startedBy(user.id(), "다른 대화", conversation.agentId(), Instant.now()));
        AgentExecution otherTurn = executions.save(AgentExecution.builder()
                .userId(user.id())
                .conversationId(otherConversation.id())
                .profileName(CHIEF_PROFILE)
                .hermesSessionId("fos-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.parse("2026-09-30T00:00:00Z"))
                .build());
        TurnCancellation.TurnHandle other = turns.open(user.id(), otherConversation.id());
        try {
            turns.rekey(other, otherTurn.id());

            DelegationResult started = delegate("turn 이 끝난 뒤 맡긴 일");
            String runId =
                    executions.findById(started.executionId()).orElseThrow().hermesRunId();
            assertThat(runId).as("제출까지 기다렸다").isNotNull();
            assertThat(awaiting.await(10, TimeUnit.SECONDS))
                    .as("자식이 run 을 붙일 자리를 지났다")
                    .isTrue();

            assertThat(turns.pendingStops(ended)).as("끝난 turn 에 붙은 run").isEmpty();
            assertThat(turns.pendingStops(other)).as("다른 대화의 도는 turn 에 붙은 run").isEmpty();
            assertThat(delegations
                            .stop(user, origin, started.executionId())
                            .orElseThrow()
                            .execution()
                            .status())
                    .isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(turns.pendingStops(other))
                    .as("자식이 끝난 뒤 다른 대화의 turn 에 붙은 run")
                    .isEmpty();
        } finally {
            turns.close(other);
        }
    }

    @Test
    @DisplayName("남의 실행과 다른 대화의 실행은 멈추지 않고 없는 실행과 같다")
    void doesNotStopOthersOrOtherConversationRunsAndTreatsAsMissing() throws Exception {
        stub().willAnswer(command -> completed(command, "답"));
        holdUntilStopped();
        DelegationResult started = delegate("남이 멈추려는 일");
        AppUser other = users.findByEmail(OTHER_EMAIL)
                .orElseGet(() -> users.save(AppUser.of(OTHER_EMAIL, "나", 1L, UserRole.MEMBER, Instant.now())));
        CurrentUser otherUser =
                new CurrentUser(other.id(), other.email(), other.displayName(), other.groupId(), other.role());
        Conversation otherConversation =
                conversations.save(Conversation.startedBy(user.id(), "다른 대화", conversation.agentId(), Instant.now()));
        AgentExecution otherConversationTurn = executions.save(AgentExecution.builder()
                .userId(user.id())
                .conversationId(otherConversation.id())
                .profileName(CHIEF_PROFILE)
                .hermesSessionId("fos-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.parse("2026-09-30T00:00:00Z"))
                .build());

        assertThat(delegations.stop(otherUser, origin, started.executionId()))
                .as("남의 실행")
                .isEmpty();
        assertThat(delegations.stop(user, otherConversationTurn, started.executionId()))
                .as("다른 대화의 실행")
                .isEmpty();
        assertThat(delegations.stop(user, origin, 999_999_999L)).as("없는 실행").isEmpty();
        assertThat(stub().stopped()).isEmpty();
        assertThat(executions.findById(started.executionId()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.RUNNING);

        assertThat(delegations
                        .stop(user, origin, started.executionId())
                        .orElseThrow()
                        .execution()
                        .status())
                .isEqualTo(ExecutionStatus.CANCELLED);
        awaitFinished(started.executionId());
    }

    /**
     * 완료를 기다리는 자리에서 중지가 올 때까지 멈춰 둔다. 실제 Hermes 에서 도는 run 과 같다.
     *
     * <p>중지가 오지 않아도 10초 뒤에는 이어져 실행 스레드가 검사보다 오래 살지 않는다.
     *
     * @return 실행 스레드가 완료 대기에 들어서면 열린다. 그때는 제출 뒤의 일(run 번호 적기, turn 에 붙이기)이 모두 끝났다
     */
    private CountDownLatch holdUntilStopped() {
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch stopReceived = new CountDownLatch(1);
        stub().onStop(runId -> stopReceived.countDown());
        stub().beforeAwait(() -> {
            awaiting.countDown();
            await(stopReceived);
        });
        return awaiting;
    }

    /**
     * 완료를 기다리는 자리에서 루트 turn 의 중지가 확정될 때까지 멈춰 둔다.
     *
     * <p>확정되지 않아도 10초 뒤에는 이어져 실행 스레드가 검사보다 오래 살지 않는다.
     *
     * @return 실행 스레드가 완료 대기에 들어서면 열린다
     */
    private CountDownLatch holdUntilRootStopConfirmed() {
        Long rootId = origin.id();
        CountDownLatch awaiting = new CountDownLatch(1);
        stub().beforeAwait(() -> {
            awaiting.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!turns.isStopConfirmed(rootId) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        return awaiting;
    }

    /** 열릴 때까지 기다린다. 열리지 않아도 10초 뒤에는 이어진다. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 이 대화에서 루트 session {@code session} 으로 도는 turn 실행이다. */
    private AgentExecution turn(String session) {
        return executions.save(AgentExecution.builder()
                .userId(user.id())
                .conversationId(conversation.id())
                .profileName(CHIEF_PROFILE)
                .hermesSessionId(session)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.parse("2026-09-30T00:00:00Z"))
                .build());
    }

    private DelegationResult delegate(String task) {
        return delegations.delegate(
                user, origin, DelegationKey.of(CHIEF_PROFILE, root, root, "call_" + UUID.randomUUID()), WORKER, task);
    }

    private DelegationResult acceptedWithin(Duration limit, String task) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            DelegationResult result = delegate(task);
            if (result.accepted()) {
                return result;
            }
            assertThat(result.failure()).as("받아들이지 않은 까닭").isEqualTo(DelegationResult.Failure.BUSY);
            if (System.nanoTime() > deadline) {
                throw new AssertionError(limit + " 안에 자리가 나지 않았다");
            }
            Thread.sleep(10);
        }
    }

    private static HermesRunResult completed(HermesRunCommand command, String output) {
        return HermesRunResult.of(
                "run-" + UUID.randomUUID(),
                command.sessionId(),
                "completed",
                output,
                "example-model",
                "example-provider",
                new TokenUsage(3L, 0L, 2L, 5L));
    }

    /** 위임 실행이 끝나고 끝난 사건까지 적힐 때까지 기다린다. */
    private AgentExecution awaitFinished(Long executionId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            AgentExecution execution = executions.findById(executionId).orElseThrow();
            boolean ended =
                    executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId)).stream()
                            .anyMatch(event -> event.eventType() != ExecutionEventType.RUN_STARTED);
            if (execution.status() != ExecutionStatus.RUNNING && ended) {
                return execution;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("실행 " + executionId + " 이 끝나지 않았다. 상태: " + execution.status());
            }
            Thread.sleep(10);
        }
    }
}
