package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.orchestration.application.DelegationResult.Failure;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hermes 가 MCP {@code agent_*} 도구로 다른 에이전트를 부를 때의 경계를 판정한다(ADR-017).
 *
 * <p>요청자와 기준 실행은 호출하는 쪽이 정해 넘긴 값만 쓴다. 토큰, profile, 최근 실행, 대화로 추측하지 않는다.
 * 이 패키지는 {@code mcp} 의 타입을 import 하지 않는다. {@code mcp} 가 요청자와 origin 실행을 풀어 넘긴다.
 */
@Service
public class AgentDelegationService {

    private static final Logger log = LoggerFactory.getLogger(AgentDelegationService.class);

    /**
     * 뿌리별 잠금의 수다. 뿌리 번호로 하나를 고른다.
     *
     * <p>다른 뿌리가 같은 잠금을 쓰면 줄을 한 번 더 설 뿐 판정은 틀리지 않는다. 뿌리마다 잠금을 만들어 두면 뿌리
     * 수만큼 쌓이고, 다 쓴 잠금을 지우면 지우는 순간 다른 스레드가 옛 잠금을 쥐는 경합이 생긴다.
     */
    private static final int ROOT_LOCK_STRIPES = 64;

    private final AgentService agents;
    private final AgentExecutionRepository executions;
    private final ChildExecutionRunner children;
    private final ConversationRepository conversations;
    private final DelegationProperties properties;

    /**
     * 같은 호출 확인과 동시 한도 세기부터 실행 줄 저장까지를 뿌리별로 묶는다.
     *
     * <p>서버 하나를 전제로 JVM 안에서 잠근다. 서버가 여러 대가 되면 데이터베이스 잠금으로 옮긴다.
     */
    private final ReentrantLock[] rootLocks = new ReentrantLock[ROOT_LOCK_STRIPES];

    /** 서버 전체에서 동시에 도는 위임의 자리다. 실행을 끝까지 돈 가상 스레드가 돌려준다. */
    private final Semaphore activeDelegations;

    public AgentDelegationService(
            AgentService agents,
            AgentExecutionRepository executions,
            ChildExecutionRunner children,
            ConversationRepository conversations,
            DelegationProperties properties) {
        this.agents = agents;
        this.executions = executions;
        this.children = children;
        this.conversations = conversations;
        this.properties = properties;
        this.activeDelegations = new Semaphore(properties.maxActive());
        for (int i = 0; i < ROOT_LOCK_STRIPES; i++) {
            rootLocks[i] = new ReentrantLock();
        }
    }

    /** 요청자가 쓸 수 있고 켜진 에이전트다. 같은 profile 을 여럿이 써도 요청자마다 다르다. */
    @Transactional(readOnly = true)
    public List<Agent> list(CurrentUser user) {
        return agents.readableBy(user);
    }

    /**
     * 요청자가 물을 수 있는 위임 실행 하나를 읽는다.
     *
     * <p>물을 수 있는 실행은 {@link #canQuery} 가 정한다. 아니면 없는 실행과 같게 빈 값이다. 남의 실행이 있는지 알리지 않는다.
     * origin 실행은 끝났어도 된다. 부모 turn 이 끝난 뒤에도 Hermes 하위 에이전트가 부르기 때문이다(ADR-037).
     */
    @Transactional(readOnly = true)
    public Optional<AgentExecution> status(CurrentUser user, AgentExecution origin, Long executionId) {
        return executions.findById(executionId).filter(execution -> canQuery(user, origin, execution));
    }

    /**
     * 다른 에이전트의 실행을 origin 실행의 자식으로 시작하고, Hermes 제출까지만 기다린다(ADR-017 「{@code agent_delegate} 는
     * 기다리지 않는다」).
     *
     * <p>판정은 이 순서로 한다. 부모의 대화, 깊이, 에이전트, 같은 호출, 뿌리당 동시 한도, 전체 한도, 실행 시작, 제출 대기다.
     * 앞의 셋은 잠그지 않고 기다리지 않는다. 같은 호출 확인부터 실행 줄 저장까지는 뿌리별로 잠가, 세기와 시작 사이에
     * 다른 위임이 끼어들지 못하게 한다. 제출 대기는 잠금 밖에서 한다. 잠금이 제출 대기까지 덮으면 같은 뿌리의 위임이
     * 모두 한 줄로 늘어선다.
     *
     * <p>실행은 가상 스레드 하나에서 끝까지 돌고, 답은 그 실행 줄의 {@code output_text} 에 SUCCEEDED 와 함께 적힌다.
     * 제출 대기가 {@link DelegationProperties#submitTimeout()} 을 넘어도 실행 줄이 생겼으면 번호를 돌려준다. 줄도 생기지
     * 않았으면 거절하고, 뒤늦게 줄이 생겨도 제출하지 않고 CANCELLED 로 끝나게 한다. Chief 가 번호를 모르는 실행이
     * Hermes 에서 돌지 않게 하기 위해서다.
     *
     * <p>트랜잭션을 걸지 않는다. 같은 키의 줄 저장이 유일 제약에 걸리면 먼저 저장된 줄을 다시 읽어야 하는데, 한 트랜잭션
     * 안에서는 MySQL REPEATABLE READ 가 그 줄을 보여 주지 않는다. Hermes 도 트랜잭션 밖에서 부른다.
     *
     * @param user 요청자. origin 실행의 사용자다
     * @param origin 이 호출의 session 을 낳은 실행. 새 자식의 부모다
     * @param delegationKey 이 도구 호출의 키. 같은 호출이 다시 오면 같은 값이다
     * @param agentCode 맡길 에이전트
     * @param task 그 에이전트에게 줄 지시. 부르는 쪽이 길이와 빈 값을 검사했다
     */
    public DelegationResult delegate(
            CurrentUser user, AgentExecution origin, DelegationKey delegationKey, String agentCode, String task) {
        Optional<Conversation> conversation = conversationOf(user, origin);
        if (conversation.isEmpty()) {
            return rejected(Failure.SUBMIT_FAILED, origin, "부모 실행에 대화가 없다");
        }
        if (depthOfChild(origin) > properties.maxDepth()) {
            return rejected(Failure.DEPTH_EXCEEDED, origin, "깊이 한도를 넘는다");
        }
        Agent agent;
        try {
            agent = children.startableAgent(user, agentCode);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.AGENT_DISABLED) return rejected(Failure.AGENT_DISABLED, origin, "꺼진 에이전트다");
            if (ex.code() == ErrorCode.AGENT_NOT_FOUND) {
                return rejected(Failure.AGENT_UNAVAILABLE, origin, "없거나 쓸 수 없는 에이전트다");
            }
            throw ex;
        }

        Long rootId = origin.treeRootId();
        long deadline = System.nanoTime() + properties.submitTimeout().toNanos();
        Handoff handoff;
        ReentrantLock lock = lockOf(rootId);
        lock.lock();
        try {
            Optional<AgentExecution> existing = executions.findByDelegationKey(delegationKey.value());
            if (existing.isPresent()) {
                return sameCall(user, origin, existing.get());
            }
            if (executions.countByRootExecutionIdAndStatusAndDelegationKeyIsNotNull(rootId, ExecutionStatus.RUNNING)
                    >= properties.maxConcurrentChildren()) {
                return rejected(Failure.TOO_MANY_CHILDREN, origin, "뿌리당 동시 위임 한도에 닿았다");
            }
            if (!activeDelegations.tryAcquire()) {
                return rejected(Failure.BUSY, origin, "서버 전체 동시 위임 한도에 닿았다");
            }
            handoff = new Handoff();
            Handoff started = handoff;
            try {
                Thread.ofVirtual()
                        .name("agent-delegate-" + rootId)
                        .start(() -> run(user, conversation.get(), origin, agent, task, delegationKey, started));
            } catch (RuntimeException | Error ex) {
                activeDelegations.release();
                log.warn("위임 실행 스레드를 띄우지 못했다 originExecutionId={}", origin.id(), ex);
                return DelegationResult.rejected(Failure.SUBMIT_FAILED);
            }
            // 실행 줄이 생기거나 줄을 만들기 전에 실패할 때까지만 잠금을 쥔다.
            if (!handoff.awaitRowDecided(deadline) && handoff.abandon()) {
                return rejected(Failure.SUBMIT_FAILED, origin, "제한 시간 안에 실행 줄이 생기지 않았다");
            }
        } finally {
            lock.unlock();
        }

        if (!handoff.rowCreated()) {
            if (handoff.failure() instanceof DataIntegrityViolationException) {
                // 같은 키를 다른 요청이 먼저 저장했다. 트랜잭션 밖에서 그 줄을 다시 읽는다.
                return executions.findByDelegationKey(delegationKey.value())
                        .map(raced -> sameCall(user, origin, raced))
                        .orElseGet(() -> rejected(Failure.SUBMIT_FAILED, origin, "저장에 실패했고 다시 읽은 줄도 없다"));
            }
            return rejected(Failure.SUBMIT_FAILED, origin, "실행 줄을 만들지 못했다");
        }
        AgentExecution execution = handoff.execution();
        boolean settled = handoff.awaitSettled(deadline);
        if (settled && !handoff.submitted()) {
            return rejected(Failure.SUBMIT_FAILED, origin, "제출하기 전에 실행이 끝났다 executionId=" + execution.id());
        }
        // 제한 시간이 지나도 줄이 있으면 번호를 돌려준다. 뒤따르는 결과는 그 줄에 적힌다.
        return DelegationResult.started(execution.id(), ExecutionStatus.RUNNING);
    }

    /**
     * 가상 스레드에서 실행 하나를 끝까지 돌린다.
     *
     * <p>어떻게 끝나든 전체 한도 자리를 돌려주고 요청 스레드를 깨운다. 실행 줄 저장이 예외를 던진 경로도 같다.
     */
    private void run(
            CurrentUser user,
            Conversation conversation,
            AgentExecution origin,
            Agent agent,
            String task,
            DelegationKey delegationKey,
            Handoff handoff) {
        RuntimeException failure = null;
        try {
            children.delegate(
                    user,
                    conversation,
                    origin,
                    agent,
                    task,
                    delegationKey,
                    handoff::onRowCreated,
                    (execution, runId) -> handoff.markSubmitted(),
                    handoff::abandoned);
        } catch (DataIntegrityViolationException ex) {
            failure = ex;
            log.info("같은 위임 키의 실행 줄이 먼저 저장됐다 originExecutionId={}", origin.id());
        } catch (RuntimeException ex) {
            failure = ex;
            log.warn("위임 실행이 예외로 끝났다 originExecutionId={}", origin.id(), ex);
        } finally {
            activeDelegations.release();
            handoff.markEnded(failure);
        }
    }

    /**
     * 같은 키로 이미 있는 실행을 돌려준다.
     *
     * <p>키가 같으면 서명한 profile 과 session 과 도구 호출이 같으므로 요청자도 같아야 한다. 다르면 번호를 알리지 않는다.
     */
    private DelegationResult sameCall(CurrentUser user, AgentExecution origin, AgentExecution existing) {
        if (!Objects.equals(existing.userId(), user.id())) {
            return rejected(Failure.SUBMIT_FAILED, origin, "같은 위임 키의 실행이 다른 사용자의 것이다");
        }
        return DelegationResult.started(existing.id(), existing.status());
    }

    /**
     * origin 실행의 대화다. 자식 실행이 그 대화의 모델 선택을 쓴다.
     *
     * <p>운영에서 대화 없는 origin 은 생기지 않는다. 생기면 실행 줄을 만들지 않고 거절한다.
     */
    private Optional<Conversation> conversationOf(CurrentUser user, AgentExecution origin) {
        if (origin.conversationId() == null) {
            return Optional.empty();
        }
        return conversations.findByIdAndUserIdAndDeletedAtIsNull(origin.conversationId(), user.id());
    }

    /**
     * 새 자식의 깊이다. origin 에서 {@code parent_execution_id} 를 따라 올라가 센다. 사용자가 부른 실행이 0 이다.
     *
     * <p>한도를 넘는 것이 정해지면 더 따라가지 않는다. 부모 사슬이 순환하는 데이터에서도 멈춘다.
     */
    private int depthOfChild(AgentExecution origin) {
        int depth = 1;
        Long parentId = origin.parentExecutionId();
        while (parentId != null && depth <= properties.maxDepth()) {
            depth++;
            parentId = executions.findById(parentId).map(AgentExecution::parentExecutionId).orElse(null);
        }
        return depth;
    }

    private ReentrantLock lockOf(Long rootId) {
        return rootLocks[Math.floorMod(rootId.hashCode(), ROOT_LOCK_STRIPES)];
    }

    /** 이유는 로그에만 남긴다. 밖으로는 실패 코드만 나간다. */
    private static DelegationResult rejected(Failure failure, AgentExecution origin, String reason) {
        log.warn("위임을 시작하지 않았다 originExecutionId={} failure={} reason={}", origin.id(), failure, reason);
        return DelegationResult.rejected(failure);
    }

    /**
     * 부르는 쪽이 이 실행을 물을 수 있는지 판정한다(ADR-017 「도구 넷과 한도」).
     *
     * <p>요청자의 실행이고 위임으로 만든 실행({@code delegation_key} 가 있음)이어야 한다. 범위는 origin 실행의 대화다.
     * 대화의 turn 마다 뿌리 실행이 새로 생기므로, 앞 turn 에서 맡긴 실행을 다음 turn 에서 물으려면 나무가 아니라 대화로
     * 견줘야 한다. origin 실행에 대화가 없으면 같은 실행 나무로 견준다. 같은 사용자의 다른 대화는 물을 수 없다.
     */
    private boolean canQuery(CurrentUser user, AgentExecution origin, AgentExecution execution) {
        if (!Objects.equals(execution.userId(), user.id()) || execution.delegationKey() == null) {
            return false;
        }
        if (origin.conversationId() != null) {
            return Objects.equals(execution.conversationId(), origin.conversationId());
        }
        return Objects.equals(execution.treeRootId(), origin.treeRootId());
    }

    /**
     * 요청 스레드와 실행 스레드가 주고받는 상태다.
     *
     * <p>「실행 줄이 생겼다」 와 「요청 스레드가 포기했다」 는 {@link #stage} 하나에서 compareAndSet 으로 정한다. 둘을
     * 따로 두면 요청 스레드가 줄이 없다고 본 직후 줄이 생겨 취소 확인을 지나 제출될 수 있다. 그러면 도구는 실패를 줬는데
     * 실행은 Hermes 에서 끝까지 돈다.
     */
    private static final class Handoff {

        private enum Stage {
            /** 실행 줄을 아직 만들지 않았다 */
            WAITING,
            /** 실행 줄이 생겼다 */
            ROW_CREATED,
            /** 실행 줄을 만들기 전에 끝났다 */
            NO_ROW,
            /** 요청 스레드가 기다리다 포기했다. 뒤늦게 줄이 생기면 제출하지 않고 CANCELLED 로 끝난다 */
            ABANDONED
        }

        private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.WAITING);
        /** 실행 줄이 생겼거나 줄 없이 끝났을 때 열린다. */
        private final CountDownLatch rowDecided = new CountDownLatch(1);
        /** 제출했거나 실행이 끝났을 때 열린다. */
        private final CountDownLatch settled = new CountDownLatch(1);
        private volatile AgentExecution execution;
        private volatile boolean submitted;
        private volatile RuntimeException failure;

        /** 실행 스레드가 실행 줄을 만든 직후 부른다. 요청 스레드가 먼저 포기했으면 상태는 그대로 ABANDONED 다. */
        void onRowCreated(AgentExecution created) {
            execution = created;
            stage.compareAndSet(Stage.WAITING, Stage.ROW_CREATED);
            rowDecided.countDown();
        }

        void markSubmitted() {
            submitted = true;
            settled.countDown();
        }

        /** 실행 스레드가 끝날 때 부른다. 줄을 만들기 전에 끝났으면 그 예외를 남긴다. */
        void markEnded(RuntimeException cause) {
            failure = cause;
            stage.compareAndSet(Stage.WAITING, Stage.NO_ROW);
            rowDecided.countDown();
            settled.countDown();
        }

        /** 요청 스레드가 포기한다. 실행 줄이 이미 생겼으면 거짓이고 그 줄을 돌려줘야 한다. */
        boolean abandon() {
            return stage.compareAndSet(Stage.WAITING, Stage.ABANDONED);
        }

        boolean abandoned() {
            return stage.get() == Stage.ABANDONED;
        }

        boolean rowCreated() {
            return stage.get() == Stage.ROW_CREATED;
        }

        boolean submitted() {
            return submitted;
        }

        AgentExecution execution() {
            return execution;
        }

        RuntimeException failure() {
            return failure;
        }

        boolean awaitRowDecided(long deadline) {
            return await(rowDecided, deadline);
        }

        boolean awaitSettled(long deadline) {
            return await(settled, deadline);
        }

        /** 끊기면 제한 시간이 지난 것과 같게 본다. 끊긴 표시는 되살린다. */
        private static boolean await(CountDownLatch latch, long deadline) {
            try {
                return latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
