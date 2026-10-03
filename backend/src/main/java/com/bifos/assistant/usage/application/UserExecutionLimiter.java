package com.bifos.assistant.usage.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.model.ExecutionAdmission;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 한 사용자가 Hermes 에 동시에 맡긴 실행의 수를 센다(ADR-069, {@code docs/backend/execution-limit.md}).
 *
 * <p>사용자가 쥔 자리는 turn 자리, 원격 종료 확인 자리, 대화 turn 의 루트가 아닌 {@code RUNNING} 실행 줄의 합이다. 판정과
 * 자리 만들기는 사용자 잠금 하나 안에서 한다. turn 자리와 실행 줄을 각자 세고 각자 만들면 두 경로가 나란히 통과해 합이 한도를
 * 넘는다.
 *
 * <p>turn 자리와 잠금은 프로세스 메모리에 둔다. Control Plane 이 한 대라는 전제다. 실행 줄은 데이터베이스에서 세므로, 줄의
 * 상태가 바뀌면 따로 돌려주지 않아도 자리가 빈다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UserExecutionLimiter {
    private final UserExecutionProperties properties;
    private final AgentExecutionRepository executions;

    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<Long, Integer> turnSlots = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> remoteEndHolds = new ConcurrentHashMap<>();

    /**
     * 대화 turn 의 자리를 얻는다.
     *
     * @throws ApiException {@code USER_BUSY}. 쥔 자리가 이미 {@code max-running} 이상이다
     */
    public TurnSlot acquireTurn(Long userId) {
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            Usage usage = usage(userId);
            if (usage.total() >= properties.maxRunning()) {
                throw reject(userId, "turn", usage);
            }
            return addTurn(userId);
        } finally {
            lock.unlock();
        }
    }

    /** 한도를 보지 않고 turn 자리를 얻는다. 기동 정리 전용이다. 그 turn 은 이미 Hermes 에서 돌고 있어 거절할 수 없다. */
    public TurnSlot acquireRecoveredTurn(Long userId) {
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            return addTurn(userId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 실행 줄을 만들기 전에 사용자 한도를 보고, 통과하면 같은 잠금 안에서 {@code create} 를 부른다.
     *
     * <p>{@code TURN_ROOT} 는 turn 자리가 이미 그 turn 을 세었으므로 보지 않는다. {@code CHILD} 와 {@code BACKGROUND} 는
     * 줄의 커밋이 잠금 안에서 끝나야 다음 판정이 그 줄을 센다. 그래서 활성 트랜잭션 안에서 부르면 거절한다.
     *
     * @throws ApiException {@code USER_BUSY}. {@code create} 를 부르지 않는다
     * @throws IllegalStateException {@code CHILD} 나 {@code BACKGROUND} 를 활성 트랜잭션 안에서 불렀다
     */
    public <T> T admit(Long userId, ExecutionAdmission admission, Supplier<T> create) {
        if (admission == ExecutionAdmission.TURN_ROOT) {
            return create.get();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "execution rows for " + admission + " must be created outside a transaction");
        }
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            Usage usage = usage(userId);
            boolean full = admission == ExecutionAdmission.CHILD
                    ? usage.total() >= properties.maxRunning()
                    : usage.total() + 1 + properties.backgroundReserve() > properties.maxRunning();
            if (full) {
                throw reject(userId, admission.name(), usage);
            }
            return create.get();
        } finally {
            lock.unlock();
        }
    }

    /** turn 자리가 하나 남았는지 본다. 자리를 만들지 않는다. */
    public boolean hasTurnRoom(Long userId) {
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            return usage(userId).total() < properties.maxRunning();
        } finally {
            lock.unlock();
        }
    }

    /** 그 사용자가 지금 쥔 자리 수다. turn 자리, 원격 종료 확인 자리, 대화 turn 의 루트가 아닌 {@code RUNNING} 실행 줄의 합이다. */
    public int used(Long userId) {
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            return usage(userId).total();
        } finally {
            lock.unlock();
        }
    }

    /** {@link TurnSlot#release()} 가 부른다. 0 이 되면 맵에서 지운다. */
    void releaseTurn(Long userId) {
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            turnSlots.computeIfPresent(userId, (id, count) -> count <= 1 ? null : count - 1);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockOf(Long userId) {
        return locks.computeIfAbsent(userId, id -> new ReentrantLock());
    }

    /** 잠금을 쥔 채 부른다. */
    private TurnSlot addTurn(Long userId) {
        turnSlots.merge(userId, 1, Integer::sum);
        return new TurnSlot(this, userId);
    }

    /** 잠금을 쥔 채 부른다. */
    private Usage usage(Long userId) {
        int turns = turnSlots.getOrDefault(userId, 0);
        int remote = remoteEndHolds.getOrDefault(userId, Set.of()).size();
        long rows = executions.countRunningOutsideTurns(userId, ExecutionStatus.RUNNING);
        return new Usage(turns, remote, rows);
    }

    private ApiException reject(Long userId, String kind, Usage usage) {
        log.info(
                "사용자 실행 한도에 닿아 거절했다 userId={} kind={} turnSlots={} remoteEndHolds={} runningRows={} maxRunning={}",
                userId,
                kind,
                usage.turns(),
                usage.remote(),
                usage.rows(),
                properties.maxRunning());
        return new ApiException(ErrorCode.USER_BUSY, "this user has reached the concurrent execution limit");
    }

    /** 한 번 센 세 값이다. 잠금 밖으로 나가지 않는다. */
    private record Usage(int turns, int remote, long rows) {
        int total() {
            return Math.toIntExact(turns + remote + rows);
        }
    }
}
