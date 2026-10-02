package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.model.RecoveredRunKind;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunLookup;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동할 때 {@code RUNNING} 으로 남은 실행을 Hermes 에 물어 정한다(ADR-059).
 *
 * <p>두 단계로 돈다. <b>잡기</b>({@link #claim})는 웹 서버가 요청을 받기 전에 끝난다. 남은 줄을 읽고 대화 turn 과
 * 흐름 turn 의 뿌리 줄마다 그 대화의 turn 잠금을 잡는다. Hermes 를 부르지 않는다. <b>묻기</b>({@link #reconcile})는
 * 애플리케이션이 다 뜬 뒤에 돈다. 줄을 적거나 잠금을 풀면 {@link NextTurnDispatcher} 가 turn 을 여는데, 그것은 다 뜬
 * 뒤여야 하기 때문이다.
 *
 * <p>여기서는 turn 을 열지 않는다. 잠금을 풀면 닫기 리스너가 다음 turn 을 정한다. 위임 실행과 그 밖의 실행은 자기
 * Hermes session 으로 돌아 대화의 session 과 겹치지 않으므로 잠금을 잡지 않는다.
 *
 * <p>내려갈 때 묻던 스레드는 줄을 적지 않고 잠금도 풀지 않고 끝난다. 줄이 {@code RUNNING} 으로 남아 다음 기동이
 * 다시 정한다. 갈리는 지점의 표는 {@code docs/backend/turn-control.md} 의 「기동할 때 남은 실행 정리」 가 갖는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestartReconciler implements SmartLifecycle {

    /** 물을 run 번호나 물을 주소가 없는 실행이다. */
    static final String ORPHANED = "ORPHANED";

    /** Hermes 가 그 run 을 모른다. */
    static final String REMOTE_RUN_LOST = "REMOTE_RUN_LOST";

    /** 상한까지 끝나지 않았다. 그동안 Hermes 의 답을 한 번은 받았다. */
    static final String RECONCILE_TIMEOUT = "RECONCILE_TIMEOUT";

    /** 상한까지 Hermes 의 답을 한 번도 받지 못했다. */
    static final String RECONCILE_UNREACHABLE = "RECONCILE_UNREACHABLE";

    /** 닿지 못해 다시 묻는 간격은 여기까지만 늘린다. */
    private static final Duration MAX_RETRY_INTERVAL = Duration.ofSeconds(5);

    private final RestartReconcileProperties properties;
    private final HermesProperties hermesProperties;
    private final HermesRunsClient hermes;
    private final AgentExecutionRepository executions;
    private final AgentService agents;
    private final RecoveredRunRecorder recorder;
    private final TurnCancellation turns;
    private final ChatPendingMessageRepository pendingMessages;
    private final TransactionTemplate transactions;

    /** 잡았지만 아직 묻기 시작하지 않은 줄이다. 실행 번호가 열쇠다. {@code this} 로 지킨다. */
    private final Map<Long, Claimed> pending = new LinkedHashMap<>();

    /** 이 클래스가 잡은 turn 잠금이다. 대화 번호가 열쇠다. {@code this} 로 지킨다. */
    private final Map<Long, ConversationLock> locks = new HashMap<>();

    /** 지금 정하고 있는 실행 번호다. 같은 줄에 스레드를 둘 띄우지 않으려고 둔다. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 묻고 있는 스레드다. 내려갈 때 깨운다. */
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();

    /** 마지막 묻기 뒤에 잡기를 했다. {@code this} 로 지킨다. */
    private boolean claimed;

    private volatile boolean stopping;

    /** {@link #stop} 을 부른 횟수다. 그 전에 뜬 스레드가 다시 {@link #start} 한 뒤에 줄을 적지 않게 한다. */
    private volatile int stopCount;

    private volatile boolean running;

    /** 웹 서버를 여는 lifecycle 보다 먼저 시작한다. 그 사이 들어온 보내기가 같은 session 에 turn 을 열지 못한다. */
    @Override
    public int getPhase() {
        return WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1;
    }

    @Override
    public void start() {
        discardLeftovers();
        stopping = false;
        if (properties.enabled()) {
            try {
                claim();
            } catch (RuntimeException ex) {
                // 잡지 못해도 기동은 이어 간다. 묻기가 시작할 때 다시 잡는다.
                log.error("기동할 때 남은 실행을 잡지 못했다", ex);
            }
        }
        running = true;
    }

    /** 내려가는 중이라고 표시하고 묻던 스레드를 깨운다. 그 스레드는 줄을 적지 않고 끝난다. */
    @Override
    public void stop() {
        stopping = true;
        stopCount++;
        running = false;
        threads.forEach(Thread::interrupt);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** {@link NextTurnDispatcher} 의 기동 뒤 깨우기보다 먼저 돈다. 잠금이 잡힌 대화는 그 깨우기가 건너뛴다. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void onReady() {
        if (properties.enabled()) {
            reconcile();
        }
    }

    /**
     * {@code RUNNING} 줄을 읽어 기억하고, 대화 turn 과 흐름 turn 의 뿌리 줄마다 그 대화의 turn 잠금을 잡는다.
     *
     * <p>Hermes 를 부르지 않고 실행 줄을 고치지 않는다. 이미 기억했거나 정하고 있는 줄은 건너뛴다.
     */
    public synchronized void claim() {
        for (AgentExecution row : executions.findByStatus(ExecutionStatus.RUNNING)) {
            if (pending.containsKey(row.id()) || inFlight.contains(row.id())) {
                continue;
            }
            pending.put(row.id(), new Claimed(row, lockFor(row)));
        }
        claimed = true;
    }

    /** 기억한 줄을 설정한 상한으로 정한다. */
    public void reconcile() {
        reconcile(properties.maxWait() == null ? hermesProperties.runTimeout() : properties.maxWait());
    }

    /**
     * 기억한 줄을 정한다. 잡기 없이 불리면 잡기부터 한다.
     *
     * <p>run 번호나 에이전트 행이 없는 줄은 묻지 않고 실패로 적는다. 나머지는 줄마다 가상 스레드 하나가 끝날 때까지
     * 묻는다. 이 메서드는 그 스레드를 기다리지 않는다.
     *
     * @param maxWait 줄 하나에 다시 붙어 기다리는 상한
     */
    void reconcile(Duration maxWait) {
        List<Claimed> batch;
        synchronized (this) {
            if (!claimed) {
                claim();
            }
            claimed = false;
            batch = new ArrayList<>(pending.values());
            pending.clear();
            batch.forEach(it -> inFlight.add(it.row().id()));
        }
        int epoch = stopCount;
        for (Claimed item : batch) {
            begin(item, maxWait, epoch);
        }
    }

    /** 대화 turn 이나 흐름 turn 의 뿌리 줄이면 그 대화의 잠금을 잡는다. 아니면 null 이다. */
    private ConversationLock lockFor(AgentExecution row) {
        if (row.hermesRunId() == null || row.parentExecutionId() != null || row.conversationId() == null) {
            return null;
        }
        ConversationLock lock = locks.get(row.conversationId());
        if (lock != null) {
            // 같은 대화에 뿌리 줄이 둘이다. 먼저 잡은 잠금을 함께 쓰고 둘 다 정해진 뒤에 푼다.
            lock.remaining++;
        } else {
            TurnCancellation.TurnHandle handle;
            try {
                handle = turns.open(row.userId(), row.conversationId());
            } catch (ApiException ex) {
                if (ex.code() != ErrorCode.CONVERSATION_BUSY) {
                    throw ex;
                }
                // 이 클래스가 잡지 않은 잠금이다. 그 turn 이 닫힐 때 다음 turn 이 정해지므로 잠금 없이 정한다.
                log.warn(
                        "다른 turn 이 잠금을 쥐고 있어 잡지 못했다 conversationId={} executionId={}",
                        row.conversationId(),
                        row.id());
                return null;
            }
            turns.rekey(handle, row.id());
            lock = new ConversationLock(row.conversationId(), row.id(), handle);
            locks.put(row.conversationId(), lock);
        }
        Long markedExecutionId = lock.executionId;
        agents.findById(row.agentId())
                .ifPresent(agent ->
                        turns.trackRun(markedExecutionId, agent.apiBaseUrl(), row.profileName(), row.hermesRunId()));
        return lock;
    }

    /** 물을 수 없는 줄은 곧바로 실패로 적고, 물을 수 있는 줄은 가상 스레드를 띄운다. */
    private void begin(Claimed item, Duration maxWait, int epoch) {
        AgentExecution row = item.row();
        Agent agent;
        try {
            agent = row.hermesRunId() == null
                    ? null
                    : agents.findById(row.agentId()).orElse(null);
            if (agent == null) {
                recorder.failWithout(row.id(), ORPHANED);
            }
        } catch (RuntimeException ex) {
            log.error("물을 수 없는 실행을 실패로 적지 못했다 executionId={}", row.id(), ex);
            agent = null;
        }
        if (agent == null) {
            finish(item, epoch);
            return;
        }
        Agent target = agent;
        try {
            Thread.ofVirtual().name("restart-reconcile-" + row.id()).start(() -> follow(item, target, maxWait, epoch));
        } catch (RuntimeException | Error ex) {
            log.warn("남은 실행을 정할 스레드를 띄우지 못했다 executionId={}", row.id(), ex);
            finish(item, epoch);
        }
    }

    private void follow(Claimed item, Agent agent, Duration maxWait, int epoch) {
        Thread current = Thread.currentThread();
        threads.add(current);
        try {
            settleByAsking(item, agent, maxWait, epoch);
        } finally {
            threads.remove(current);
            finish(item, epoch);
        }
    }

    /**
     * 줄 하나를 Hermes 에 물어 정한다. 끝난 답을 적거나 상한을 넘길 때까지 되풀이한다.
     *
     * <p>조회가 던진 예외와 적다가 난 예외를 같게 다룬다. 실패로 적지 않고 다음 바퀴에 다시 묻는다. Hermes 가 끝난
     * 답을 한동안 갖고 있어 같은 답을 다시 받는다. 끝은 상한이 보장한다.
     */
    private void settleByAsking(Claimed item, Agent agent, Duration maxWait, int epoch) {
        AgentExecution row = item.row();
        String runId = row.hermesRunId();
        // 중지 표시가 붙은 뿌리 실행 번호다. 잠금을 잡지 않은 줄은 표시가 없어 아무 일도 없다.
        Long markedExecutionId = item.lock() == null ? row.treeRootId() : item.lock().executionId;
        long deadline = System.nanoTime() + maxWait.toNanos();
        boolean reached = false;
        boolean stopSent = false;
        RecoveredRunKind kind = null;
        Duration interval = hermesProperties.pollInterval();
        while (true) {
            if (halted(epoch)) {
                return;
            }
            try {
                HermesRunLookup lookup = hermes.lookupRun(agent.apiBaseUrl(), row.profileName(), runId);
                if (lookup.state() == HermesRunLookup.State.FINISHED) {
                    turns.untrackRun(markedExecutionId, runId);
                    // 거짓이면 다른 쪽이 이미 적었다. 어느 쪽이든 이 줄은 정해졌다.
                    recorder.settle(row.id(), lookup.result());
                    return;
                }
                if (lookup.state() == HermesRunLookup.State.NOT_FOUND) {
                    turns.untrackRun(markedExecutionId, runId);
                    recorder.failWithout(row.id(), REMOTE_RUN_LOST);
                    return;
                }
                reached = true;
                interval = hermesProperties.pollInterval();
                if (kind == null) {
                    kind = recorder.kindOf(row);
                }
                // 흐름의 단계 순서는 내려간 프로세스의 메모리에만 있었다. 이어 갈 수 없으므로 멈추고 끝난 상태만 적는다.
                if (kind == RecoveredRunKind.FLOW && !stopSent) {
                    stopSent = sendStop(agent, row);
                }
            } catch (RuntimeException ex) {
                if (halted(epoch)) {
                    return;
                }
                interval = interval.multipliedBy(2).compareTo(MAX_RETRY_INTERVAL) > 0
                        ? MAX_RETRY_INTERVAL
                        : interval.multipliedBy(2);
                log.warn("남은 실행을 정하지 못해 다시 묻는다 executionId={} runId={}", row.id(), runId, ex);
            }
            if (System.nanoTime() - deadline >= 0) {
                giveUp(agent, row, reached);
                return;
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 상한을 넘긴 실행에 중지를 보내고 실패로 적는다.
     *
     * <p>Hermes 의 취소 결과를 기다리지 않는다. 닿지 않아 넘긴 경우가 있어 기다림에 끝이 없다. 적다가 실패하면 줄이
     * {@code RUNNING} 으로 남아 다음 기동이 다시 정한다.
     */
    private void giveUp(Agent agent, AgentExecution row, boolean reached) {
        sendStop(agent, row);
        try {
            recorder.failWithout(row.id(), reached ? RECONCILE_TIMEOUT : RECONCILE_UNREACHABLE);
        } catch (RuntimeException ex) {
            log.error("상한을 넘긴 실행을 실패로 적지 못했다 executionId={}", row.id(), ex);
        }
    }

    /** 보내지 못하면 경고 로그만 남기고 거짓을 돌려준다. */
    private boolean sendStop(Agent agent, AgentExecution row) {
        try {
            hermes.stop(agent.apiBaseUrl(), row.profileName(), row.hermesRunId());
            return true;
        } catch (RuntimeException ex) {
            log.warn("남은 실행에 중지를 보내지 못했다 executionId={} runId={}", row.id(), row.hermesRunId(), ex);
            return false;
        }
    }

    /** 이 스레드가 뜬 뒤에 내려가기 시작했다. 그 뒤로는 줄을 적지 않고 잠금도 풀지 않는다. */
    private boolean halted(int epoch) {
        return stopping || stopCount != epoch;
    }

    /** 줄 하나를 다 정했다. 내려가는 중이 아니면 잡은 잠금을 푼다. */
    private void finish(Claimed item, int epoch) {
        inFlight.remove(item.row().id());
        if (!halted(epoch)) {
            release(item.lock());
        }
    }

    /**
     * 그 대화의 남은 줄이 없으면 잠금을 한 번 푼다.
     *
     * <p>사용자가 중지해 취소로 끝난 turn 이면 풀기 전에 대기 줄을 멈춘다. 풀고 나서 멈추면 그 사이 닫기 리스너가
     * 아직 멈추지 않은 행으로 turn 을 연다. 대기 줄이 멈췄다는 알림은 닫기 리스너가 낸다.
     */
    private void release(ConversationLock lock) {
        if (lock == null) {
            return;
        }
        synchronized (this) {
            lock.remaining--;
            if (lock.remaining > 0) {
                return;
            }
            locks.remove(lock.conversationId, lock);
        }
        TurnCancellation.TurnHandle handle = lock.handle;
        try {
            // 중지가 확정됐는지가 아니라 요청됐는지를 본다. 중지를 보낸 뒤 확정하기 전에 취소 결과를 먼저 적을 수 있다.
            if (handle.cancelled().get() && endedCancelled(lock.executionId)) {
                turns.markStopped(handle);
                transactions.executeWithoutResult(status -> pendingMessages.markHeld(lock.conversationId, true));
            }
        } catch (RuntimeException ex) {
            log.warn("중지한 turn 의 대기 줄을 멈춰 두지 못했다 conversationId={}", lock.conversationId, ex);
        } finally {
            turns.markFinished(handle);
            turns.close(handle);
        }
    }

    private boolean endedCancelled(Long executionId) {
        return executions
                .findById(executionId)
                .map(row -> row.status() == ExecutionStatus.CANCELLED)
                .orElse(false);
    }

    /**
     * 앞선 {@link #stop} 이 풀지 않고 둔 잠금과 기억을 버린다.
     *
     * <p>프로세스가 내려가면 함께 사라지는 것들이다. 같은 프로세스에서 다시 시작할 때만 남아 있다.
     */
    private void discardLeftovers() {
        List<ConversationLock> leftovers;
        synchronized (this) {
            leftovers = new ArrayList<>(locks.values());
            locks.clear();
            pending.clear();
            claimed = false;
        }
        inFlight.clear();
        leftovers.forEach(lock -> turns.close(lock.handle));
    }

    /** 기억한 줄 하나와, 그 줄 때문에 잡은 잠금이다. 잡지 않았으면 null 이다. */
    private record Claimed(AgentExecution row, ConversationLock lock) {}

    /** 이 클래스가 잡은 대화 하나의 turn 잠금이다. {@code remaining} 은 바깥 객체로 지킨다. */
    private static final class ConversationLock {
        private final Long conversationId;

        /** 중지 표시에 붙인 실행 번호다. 그 대화에서 먼저 잡은 줄이다. */
        private final Long executionId;

        private final TurnCancellation.TurnHandle handle;

        /** 이 잠금을 함께 쓰는 줄 가운데 아직 정해지지 않은 수다. */
        private int remaining = 1;

        private ConversationLock(Long conversationId, Long executionId, TurnCancellation.TurnHandle handle) {
            this.conversationId = conversationId;
            this.executionId = executionId;
            this.handle = handle;
        }
    }
}
