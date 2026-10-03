package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.TurnSlot;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.application.model.ExecutionAdmission;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** 사용자 자리를 turn 자리와 실행 줄의 합으로 세고, 판정과 자리 만들기를 한 잠금 안에서 하는지 본다(ADR-069). */
@SpringBootTest(
        properties = {"assistant.user-execution.max-running=3", "assistant.user-execution.background-reserve=1"})
@ActiveProfiles("test")
class UserExecutionLimiterTest {

    /** 다른 검사가 남긴 줄과 섞이지 않게 이 검사만 쓰는 큰 번호에서 사용자 번호를 하나씩 받는다. */
    private static final AtomicLong NEXT_USER_ID = new AtomicLong(96_900_000L);

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    TransactionTemplate transactions;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = NEXT_USER_ID.incrementAndGet();
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
