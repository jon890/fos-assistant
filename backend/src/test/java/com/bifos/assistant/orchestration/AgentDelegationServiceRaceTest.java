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
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        // 서버 전체 한도를 1 로 둬, 거절한 요청이 자리를 돌려주지 않고 남기면 다음 요청이 BUSY 가 된다.
        delegations = new AgentDelegationService(
                mock(AgentService.class), executions, children, conversations,
                new DelegationProperties(2, 4, 1, SUBMIT_TIMEOUT, 100),
                mock(TurnCancellation.class), mock(HermesRunsClient.class));
    }

    @Test
    @DisplayName("뿌리 잠금을 제한 시간 안에 잡지 못하면 실행을 시작하지 않고 SUBMIT FAILED 다")
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
            Future<DelegationResult> first = pool.submit(() -> delegations.delegate(user, origin, holding, WORKER, "앞 요청"));
            assertThat(holdingEntered.await(10, TimeUnit.SECONDS)).as("앞 요청이 잠금을 쥐었다").isTrue();

            waitingResult = delegations.delegate(user, origin, waiting, WORKER, "잠금을 기다리는 요청");

            releaseHolding.countDown();
            holdingResult = first.get(10, TimeUnit.SECONDS);
        }

        assertThat(waitingResult.failure()).as("잠금을 못 잡은 요청: %s", waitingResult)
                .isEqualTo(DelegationResult.Failure.SUBMIT_FAILED);
        assertThat(holdingResult.failure()).as("잠금 안에서 제한 시간이 지난 요청: %s", holdingResult)
                .isEqualTo(DelegationResult.Failure.SUBMIT_FAILED);
        verify(children, never()).delegate(any(), any(), any(), any(), any(), any(), any(), any(), any());

        startsWithRow(77L);
        DelegationResult retried = delegations.delegate(user, origin, waiting, WORKER, "같은 키로 다시 부른다");
        assertThat(retried.executionId()).as("거절한 요청이 줄과 자리를 남기지 않아 같은 키로 새로 시작한다: %s", retried)
                .isEqualTo(77L);
        assertThat(retried.status()).isEqualTo(ExecutionStatus.RUNNING);
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
        when(executions.findByDelegationKey(raced.value())).thenReturn(Optional.empty()).thenReturn(Optional.of(saved));
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
        when(executions.findByDelegationKey(raced.value())).thenReturn(Optional.empty()).thenReturn(Optional.of(saved));
        when(children.delegate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("같은 delegation_key"));

        DelegationResult result = delegations.delegate(user, origin, raced, WORKER, "같은 호출");

        assertThat(result.failure()).as("결과: %s", result).isEqualTo(DelegationResult.Failure.SUBMIT_FAILED);
        assertThat(result.executionId()).isNull();
    }

    /** 다음 실행이 줄을 만들고 곧바로 제출하게 한다. */
    private void startsWithRow(Long executionId) {
        AgentExecution row = mock(AgentExecution.class);
        when(row.id()).thenReturn(executionId);
        when(children.delegate(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
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
