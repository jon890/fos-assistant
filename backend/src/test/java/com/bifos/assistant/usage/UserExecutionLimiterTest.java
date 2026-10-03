package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunLookup;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.TurnSlot;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.application.model.ExecutionAdmission;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 자리를 turn 자리, 원격 종료 확인 자리, 실행 줄의 합으로 세고, 판정과 자리 만들기를 한 잠금 안에서 하는지 본다(ADR-069).
 *
 * <p>원격 종료 확인 자리는 대역 Hermes 의 조회 답으로 돌려주는 때를 정한다. 다시 묻는 간격은 테스트 profile 의
 * {@code hermes.poll-interval} 이다. 쥔 자리를 남기는 검사는 끝나기 전에 그 run 을 모른다고 답하게 해 돌려받는다.
 */
@SpringBootTest(
        properties = {
            "assistant.user-execution.max-running=3",
            "assistant.user-execution.background-reserve=1",
            "assistant.user-execution.remote-end-max-wait=2s"
        })
@ActiveProfiles("test")
@Import(UserExecutionLimiterTest.StubRuntime.class)
class UserExecutionLimiterTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    /** {@code remote-end-max-wait} 와 같은 값이다. */
    private static final Duration REMOTE_END_MAX_WAIT = Duration.ofSeconds(2);

    /** 자리가 돌아오기를 기다리는 한도다. 상한보다 넉넉하다. */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    private static final String API_BASE_URL = "http://agent-runtime.test/p/limit";
    private static final String PROFILE = "limit-test";

    /** 다른 검사가 남긴 줄과 섞이지 않게 이 검사만 쓰는 큰 번호에서 사용자 번호를 하나씩 받는다. */
    private static final AtomicLong NEXT_USER_ID = new AtomicLong(96_900_000L);

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    StubHermesRunsClient stub;

    private Long userId;

    /** 검사마다 새로 만든다. 앞 검사의 조회와 섞이지 않는다. */
    private String runId;

    @BeforeEach
    void setUp() {
        userId = NEXT_USER_ID.incrementAndGet();
        runId = "run-" + UUID.randomUUID();
        stub.reset();
    }

    @Test
    @DisplayName("turn 자리 셋을 얻으면 넷째는 USER_BUSY 이고, 하나를 돌려주면 다시 얻는다")
    void rejectsFourthTurnAndAcceptsAgainAfterRelease() {
        TurnSlot first = limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);

        assertThat(limiter.hasTurnRoom(userId)).isFalse();
        assertUserBusy(() -> limiter.acquireTurn(userId));
        assertThat(limiter.used(userId)).isEqualTo(3);

        first.release();

        assertThat(limiter.used(userId)).isEqualTo(2);
        assertThat(limiter.hasTurnRoom(userId)).isTrue();
        limiter.acquireTurn(userId);
        assertThat(limiter.used(userId)).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 turn 자리를 두 번 돌려줘도 한 번만 줄어든다")
    void releasesSameSlotOnlyOnce() {
        TurnSlot first = limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);

        first.release();
        first.release();

        assertThat(limiter.used(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("turn 자리 둘과 자식 줄 하나면 셋째 자식은 USER_BUSY 이고 줄을 만들지 않는다")
    void rejectsChildWhenTurnsAndChildFillLimit() {
        limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);
        limiter.admit(userId, ExecutionAdmission.CHILD, () -> saveRunning(userId, 10L, 1L));
        AtomicBoolean created = new AtomicBoolean();

        assertUserBusy(() -> limiter.admit(userId, ExecutionAdmission.CHILD, () -> {
            created.set(true);
            return saveRunning(userId, 10L, 1L);
        }));

        assertThat(created).as("거절한 자식의 줄을 만들려 했다").isFalse();
        assertThat(limiter.used(userId)).isEqualTo(3);
    }

    @Test
    @DisplayName("대화 turn 의 루트 줄은 자리로 세지 않고 추천 질문 줄은 센다")
    void countsDetachedRowButNotConversationTurnRoot() {
        saveRunning(userId, 10L, null);

        assertThat(limiter.used(userId)).as("대화 turn 의 루트 줄").isZero();

        saveRunning(userId, null, null);

        assertThat(limiter.used(userId)).as("추천 질문 줄").isEqualTo(1);
    }

    @Test
    @DisplayName("끝난 자식 줄은 자리로 세지 않는다")
    void doesNotCountFinishedRows() {
        executions.save(row(userId, 10L, 1L, ExecutionStatus.SUCCEEDED));

        assertThat(limiter.used(userId)).isZero();
    }

    @Test
    @DisplayName("백그라운드 실행은 예비 자리 하나를 남길 수 있을 때만 통과한다")
    void admitsBackgroundOnlyWhileReserveRemains() {
        limiter.acquireTurn(userId);

        AgentExecution admitted =
                limiter.admit(userId, ExecutionAdmission.BACKGROUND, () -> saveRunning(userId, null, null));

        assertThat(admitted.id()).isNotNull();
        assertThat(limiter.used(userId)).isEqualTo(2);
        assertUserBusy(
                () -> limiter.admit(userId, ExecutionAdmission.BACKGROUND, () -> saveRunning(userId, null, null)));
        assertThat(limiter.used(userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("대화 turn 의 루트 줄은 한도를 넘겨도 만든다")
    void admitsTurnRootWithoutCheckingLimit() {
        limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);

        AgentExecution root = limiter.admit(userId, ExecutionAdmission.TURN_ROOT, () -> saveRunning(userId, 10L, null));

        assertThat(root.id()).isNotNull();
    }

    @Test
    @DisplayName("기동 정리의 turn 자리는 한도를 넘겨도 얻는다")
    void acquiresRecoveredTurnBeyondLimit() {
        limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);

        limiter.acquireRecoveredTurn(userId);

        assertThat(limiter.used(userId)).isEqualTo(4);
        assertUserBusy(() -> limiter.acquireTurn(userId));
    }

    @Test
    @DisplayName("한 사용자가 한도에 닿아도 다른 사용자는 자리를 얻는다")
    void keepsLimitPerUser() {
        limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);
        Long other = NEXT_USER_ID.incrementAndGet();

        limiter.acquireTurn(other);

        assertThat(limiter.used(other)).isEqualTo(1);
    }

    @Test
    @DisplayName("turn 자리와 자식 줄을 나란히 얻어도 합이 한도를 넘지 않는다")
    void keepsSumWithinLimitUnderConcurrentTurnsAndChildren() throws Exception {
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<?>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                boolean turn = i % 2 == 0;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        if (turn) {
                            limiter.acquireTurn(userId);
                        } else {
                            limiter.admit(userId, ExecutionAdmission.CHILD, () -> {
                                // 판정과 커밋 사이를 벌린다. 잠금이 없으면 이 틈에 다른 스레드가 이 줄을 세지 못하고 통과한다.
                                pause();
                                return saveRunning(userId, 10L, 1L);
                            });
                        }
                        admitted.incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.code()).isEqualTo(ErrorCode.USER_BUSY);
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(admitted.get()).as("통과한 turn 자리와 자식 줄의 합").isEqualTo(3);
        assertThat(limiter.used(userId)).isEqualTo(3);
    }

    @Test
    @DisplayName("트랜잭션 안에서 자식 줄을 만들려 하면 거절하고 줄을 만들지 않는다")
    void rejectsChildInsideTransaction() {
        AtomicBoolean created = new AtomicBoolean();

        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> limiter.admit(userId, ExecutionAdmission.CHILD, () -> {
                            created.set(true);
                            return saveRunning(userId, 10L, 1L);
                        })))
                .isInstanceOf(IllegalStateException.class);
        assertThat(created).as("트랜잭션 안에서 줄을 만들려 했다").isFalse();
        assertThat(limiter.used(userId)).isZero();
    }

    @Test
    @DisplayName("끝났는지 모르는 run 을 쥐면 자리가 하나 늘고 중지를 한 번 보낸 뒤, 끝났다는 답을 받으면 돌려준다")
    void holdsUntilRemoteRunFinishesAndSendsStopOnce() {
        stub.willLookup(runId, HermesRunLookup.running(), HermesRunLookup.running(), finished(runId));

        limiter.holdUntilRemoteEnds(userId, 1L, API_BASE_URL, PROFILE, runId, false);

        assertThat(limiter.used(userId)).as("쥔 직후 자리").isEqualTo(1);
        awaitUntil(() -> limiter.used(userId) == 0, "끝났다는 답을 받고도 자리를 돌려주지 않았다");
        assertThat(stub.stopped()).as("보낸 중지").containsExactly(runId);
        assertThat(lookupsOf(runId)).as("끝났다는 답까지 물은 수").isEqualTo(3);
    }

    @Test
    @DisplayName("부르는 쪽이 중지를 이미 보냈으면 다시 보내지 않는다")
    void doesNotSendStopWhenAlreadySent() {
        stub.willLookup(runId, HermesRunLookup.running(), finished(runId));

        limiter.holdUntilRemoteEnds(userId, 1L, API_BASE_URL, PROFILE, runId, true);

        awaitUntil(() -> limiter.used(userId) == 0, "끝났다는 답을 받고도 자리를 돌려주지 않았다");
        assertThat(stub.stopped()).as("보낸 중지").isEmpty();
    }

    @Test
    @DisplayName("Hermes 가 그 run 을 모른다고 답하면 한 번 묻고 돌려준다")
    void releasesWhenRemoteRunIsUnknown() {
        limiter.holdUntilRemoteEnds(userId, 1L, API_BASE_URL, PROFILE, runId, true);

        awaitUntil(() -> limiter.used(userId) == 0, "모른다는 답을 받고도 자리를 돌려주지 않았다");
        assertThat(lookupsOf(runId)).as("물은 수").isEqualTo(1);
    }

    @Test
    @DisplayName("Hermes 에 닿지 못하는 동안에는 쥐고, 닿아 끝났다는 답을 받으면 돌려준다")
    void keepsHoldingWhileUnreachableThenReleases() {
        stub.willFailLookup(runId, new ApiException(ErrorCode.HERMES_UNAVAILABLE, "hermes is down"), 2);
        stub.willLookup(runId, finished(runId));

        limiter.holdUntilRemoteEnds(userId, 1L, API_BASE_URL, PROFILE, runId, true);

        awaitUntil(() -> limiter.used(userId) == 0, "닿은 뒤에도 자리를 돌려주지 않았다");
        assertThat(lookupsOf(runId)).as("닿지 못한 두 번과 끝났다는 답을 받은 한 번").isEqualTo(3);
    }

    @Test
    @DisplayName("계속 돌고 있으면 상한까지 쥐었다가 돌려준다")
    void releasesAfterMaxWaitWhileRemoteRunKeepsRunning() {
        stub.willLookup(runId, HermesRunLookup.running());
        long startedAt = System.nanoTime();

        limiter.holdUntilRemoteEnds(userId, 1L, API_BASE_URL, PROFILE, runId, true);
        awaitUntil(() -> lookupsOf(runId) >= 3, "도는 동안 다시 묻지 않았다");

        assertThat(limiter.used(userId)).as("도는 동안 자리").isEqualTo(1);
        awaitUntil(() -> limiter.used(userId) == 0, "상한을 넘기고도 자리를 돌려주지 않았다");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .as("돌려주기까지 걸린 시간")
                .isGreaterThanOrEqualTo(REMOTE_END_MAX_WAIT);
    }

    @Test
    @DisplayName("같은 실행 번호로 두 번 쥐어도 자리는 하나만 는다")
    void holdsSameExecutionOnlyOnce() {
        stub.willLookup(runId, HermesRunLookup.running());

        limiter.holdUntilRemoteEnds(userId, 1L, API_BASE_URL, PROFILE, runId, false);
        limiter.holdUntilRemoteEnds(userId, 1L, API_BASE_URL, PROFILE, runId, false);

        assertThat(limiter.used(userId)).isEqualTo(1);
        releaseRemoteRun();
        assertThat(stub.stopped()).as("두 번째 쥐기는 중지를 다시 보내지 않는다").containsExactly(runId);
    }

    @Test
    @DisplayName("run 번호가 비어 있으면 물을 수 없어 쥐지 않는다")
    void doesNotHoldWithoutRunId() {
        limiter.holdUntilRemoteEnds(userId, 1L, API_BASE_URL, PROFILE, null, false);
        limiter.holdUntilRemoteEnds(userId, 2L, API_BASE_URL, PROFILE, " ", false);

        assertThat(limiter.used(userId)).isZero();
        assertThat(stub.stopped()).isEmpty();
    }

    @Test
    @DisplayName("원격 종료 확인 자리를 쥔 동안에는 같은 사용자의 turn 이 그만큼 덜 열린다")
    void remoteEndHoldTakesTurnRoom() {
        stub.willLookup(runId, HermesRunLookup.running());
        limiter.holdUntilRemoteEnds(userId, 1L, API_BASE_URL, PROFILE, runId, true);

        limiter.acquireTurn(userId);
        limiter.acquireTurn(userId);

        assertUserBusy(() -> limiter.acquireTurn(userId));
        assertThat(limiter.used(userId)).isEqualTo(3);
        releaseRemoteRun();
        assertThat(limiter.used(userId)).as("돌려받은 뒤 남은 turn 자리").isEqualTo(2);
    }

    /** 그 run 을 모른다고 답하게 해 쥔 자리를 돌려받는다. 확인 스레드가 검사 뒤까지 묻지 않게 한다. */
    private void releaseRemoteRun() {
        int before = limiter.used(userId);
        stub.willLookup(runId, HermesRunLookup.notFound());
        awaitUntil(() -> limiter.used(userId) == before - 1, "모른다는 답을 받고도 자리를 돌려주지 않았다");
    }

    private long lookupsOf(String id) {
        return stub.lookups().stream().filter(id::equals).count();
    }

    private static HermesRunLookup finished(String id) {
        return HermesRunLookup.finished(
                HermesRunResult.of(id, "session", "completed", "답", "model", "provider", TokenUsage.empty()));
    }

    private static void awaitUntil(BooleanSupplier condition, String failure) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError(failure + " (" + WAIT_LIMIT + " 동안 기다렸다)");
            }
            pause();
        }
    }

    private void assertUserBusy(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.USER_BUSY));
    }

    private AgentExecution saveRunning(Long owner, Long conversationId, Long parentExecutionId) {
        return executions.save(row(owner, conversationId, parentExecutionId, ExecutionStatus.RUNNING));
    }

    private static AgentExecution row(Long owner, Long conversationId, Long parentExecutionId, ExecutionStatus status) {
        return AgentExecution.builder()
                .userId(owner)
                .conversationId(conversationId)
                .parentExecutionId(parentExecutionId)
                .rootExecutionId(parentExecutionId)
                .profileName("limit-test")
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(Instant.now())
                .build();
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
