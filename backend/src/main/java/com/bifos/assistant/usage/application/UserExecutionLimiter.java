package com.bifos.assistant.usage.application;

import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunLookup;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.model.ExecutionAdmission;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Duration;
import java.util.HashSet;
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
 *
 * <p>원격 종료 확인 자리도 메모리에만 둔다. 실행 줄을 먼저 끝냈는데 Hermes 에서 그 run 이 끝났는지 모를 때 쥐고, Hermes 가
 * 끝났다거나 그 run 을 모른다고 답하면 돌려준다. 재기동하면 사라진다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UserExecutionLimiter {

    /** Hermes 에 닿지 못해 다시 묻는 간격은 여기까지만 늘린다. */
    private static final Duration MAX_RETRY_INTERVAL = Duration.ofSeconds(5);

    private final UserExecutionProperties properties;
    private final AgentExecutionRepository executions;
    private final HermesRunsClient hermes;
    private final HermesProperties hermesProperties;

    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<Long, Integer> turnSlots = new ConcurrentHashMap<>();
    private final Set<Long> backgroundConversations = ConcurrentHashMap.newKeySet();
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

    /** 매일 깨우기의 turn 자리를 얻으며 사용자 대화를 위한 자리를 적어도 하나 남긴다. */
    public TurnSlot acquireBackgroundTurn(Long userId) {
        return acquireBackgroundTurn(userId, null);
    }

    /** 점검 대화를 함께 등록해 그 실행의 위임 자식도 사용자 대화 자리를 남기게 한다. */
    public TurnSlot acquireBackgroundTurn(Long userId, Long conversationId) {
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            Usage usage = usage(userId);
            int reserve = Math.max(1, properties.backgroundReserve());
            if (usage.total() + 1 + reserve > properties.maxRunning()) {
                throw reject(userId, "background-turn", usage);
            }
            turnSlots.merge(userId, 1, Integer::sum);
            if (conversationId != null) {
                backgroundConversations.add(conversationId);
            }
            return new TurnSlot(this, userId, conversationId);
        } finally {
            lock.unlock();
        }
    }

    /** 그 대화가 현재 매일 깨우기의 turn 자리를 쥐고 있다. */
    public boolean isBackgroundConversation(Long conversationId) {
        return conversationId != null && backgroundConversations.contains(conversationId);
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
            int reserve = admission == ExecutionAdmission.BACKGROUND_CHILD
                    ? Math.max(1, properties.backgroundReserve())
                    : properties.backgroundReserve();
            boolean full;
            if (admission == ExecutionAdmission.CHILD) {
                full = usage.total() >= properties.maxRunning();
            } else {
                full = usage.total() + 1 + reserve > properties.maxRunning();
            }
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

    /**
     * Hermes 에서 끝났는지 모르는 run 의 자리를 그 run 이 끝날 때까지 쥔다(ADR-069).
     *
     * <p>가상 스레드 하나가 아직 중지를 보내지 않았으면 중지를 한 번 보내고, {@code lookupRun} 이 끝났다거나 그 run 을 모른다고
     * 답할 때까지 묻는다. {@code remote-end-max-wait} 를 넘으면 경고 로그를 남기고 돌려준다. 이 메서드는 그 스레드를 기다리지
     * 않는다.
     *
     * @param runId 비어 있으면 물을 수 없어 쥐지 않는다
     * @param stopAlreadySent 부르는 쪽이 이미 중지를 보냈다. 참이면 다시 보내지 않는다
     */
    public void holdUntilRemoteEnds(
            Long userId,
            Long executionId,
            String apiBaseUrl,
            String profileName,
            String runId,
            boolean stopAlreadySent) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            // 같은 실행을 두 번 쥐지 않는다.
            if (!remoteEndHolds.computeIfAbsent(userId, id -> new HashSet<>()).add(executionId)) {
                return;
            }
        } finally {
            lock.unlock();
        }
        try {
            Thread.ofVirtual()
                    .name("remote-end-" + executionId)
                    .start(() -> followRemoteEnd(userId, executionId, apiBaseUrl, profileName, runId, stopAlreadySent));
        } catch (RuntimeException | Error ex) {
            log.warn("Hermes 에서 끝났는지 확인할 스레드를 띄우지 못해 사용자 자리를 곧바로 돌려준다 executionId={} runId={}", executionId, runId, ex);
            releaseRemoteEnd(userId, executionId);
        }
    }

    /** {@link TurnSlot#release()} 가 부른다. 0 이 되면 맵에서 지운다. */
    void releaseTurn(Long userId, Long backgroundConversationId) {
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            turnSlots.computeIfPresent(userId, (id, count) -> count <= 1 ? null : count - 1);
            if (backgroundConversationId != null) {
                backgroundConversations.remove(backgroundConversationId);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 어느 경우든 끝나면 자리를 돌려준다. */
    private void followRemoteEnd(
            Long userId,
            Long executionId,
            String apiBaseUrl,
            String profileName,
            String runId,
            boolean stopAlreadySent) {
        try {
            if (!stopAlreadySent) {
                sendStop(executionId, apiBaseUrl, profileName, runId);
            }
            awaitRemoteEnd(executionId, apiBaseUrl, profileName, runId);
        } finally {
            releaseRemoteEnd(userId, executionId);
        }
    }

    /** 보내지 못해도 묻기는 이어 간다. 그 run 이 스스로 끝나면 자리를 돌려줄 수 있다. */
    private void sendStop(Long executionId, String apiBaseUrl, String profileName, String runId) {
        try {
            hermes.stop(apiBaseUrl, profileName, runId);
        } catch (RuntimeException ex) {
            log.warn("끝났는지 모르는 run 에 중지를 보내지 못했다 executionId={} runId={}", executionId, runId, ex);
        }
    }

    /**
     * Hermes 가 그 run 이 끝났다거나 모른다고 답할 때까지, 또는 상한까지 묻는다.
     *
     * <p>아직 돌면 {@code hermes.poll-interval} 뒤 다시 묻고, 닿지 못하면 간격을 두 배씩 {@link #MAX_RETRY_INTERVAL} 까지
     * 늘린다. 끊기면 곧바로 돌아간다.
     */
    private void awaitRemoteEnd(Long executionId, String apiBaseUrl, String profileName, String runId) {
        long deadline = System.nanoTime() + remoteEndMaxWait().toNanos();
        Duration interval = hermesProperties.pollInterval();
        while (true) {
            try {
                HermesRunLookup lookup = hermes.lookupRun(apiBaseUrl, profileName, runId);
                if (lookup.state() != HermesRunLookup.State.RUNNING) {
                    return;
                }
                interval = hermesProperties.pollInterval();
            } catch (RuntimeException ex) {
                Duration doubled = interval.multipliedBy(2);
                interval = doubled.compareTo(MAX_RETRY_INTERVAL) > 0 ? MAX_RETRY_INTERVAL : doubled;
                log.warn("끝났는지 모르는 run 을 묻지 못해 다시 묻는다 executionId={} runId={}", executionId, runId, ex);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                log.warn("Hermes 에서 끝났는지 확인하지 못한 채 사용자 자리를 돌려준다 executionId={} runId={}", executionId, runId);
                return;
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(interval.toNanos(), remaining)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Duration remoteEndMaxWait() {
        return properties.remoteEndMaxWait() == null ? hermesProperties.runTimeout() : properties.remoteEndMaxWait();
    }

    /** 모음에서 빼고, 빈 모음은 맵에서 지운다. */
    private void releaseRemoteEnd(Long userId, Long executionId) {
        ReentrantLock lock = lockOf(userId);
        lock.lock();
        try {
            remoteEndHolds.computeIfPresent(userId, (id, held) -> {
                held.remove(executionId);
                return held.isEmpty() ? null : held;
            });
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
