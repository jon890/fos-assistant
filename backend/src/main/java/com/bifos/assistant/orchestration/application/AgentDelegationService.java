package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.DelegationFinished;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.orchestration.application.DelegationResult.Failure;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
@Slf4j
public class AgentDelegationService {

    /**
     * 루트별 잠금의 수다. 루트 번호로 하나를 고른다.
     *
     * <p>다른 루트가 같은 잠금을 쓰면 줄을 한 번 더 설 뿐 판정은 틀리지 않는다. 루트마다 잠금을 만들어 두면 루트
     * 수만큼 쌓이고, 다 쓴 잠금을 지우면 지우는 순간 다른 스레드가 옛 잠금을 쥐는 경합이 생긴다.
     */
    private static final int ROOT_LOCK_STRIPES = 64;

    /** {@code agent_stop} 이 CANCELLED 가 적히기를 기다리는 시간이다. 넘으면 RUNNING 을 돌려준다. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(5);

    private final AgentService agents;
    private final AgentExecutionRepository executions;
    private final ExecutionDeliveryWriter deliveryWriter;
    private final ChildExecutionRunner children;
    private final ConversationRepository conversations;
    private final DelegationProperties properties;
    private final TurnCancellation turns;
    private final HermesRunsClient hermes;
    private final ApplicationEventPublisher events;
    private final ProactiveCheckGuard checkGuard;
    private final Clock clock;

    /**
     * 이 프로세스에서 도는 위임 실행의 중지 표시와 run 참조다. 실행 번호가 열쇠다.
     *
     * <p>실행 줄이 생긴 직후 넣고 그 실행이 끝나 상태를 적은 뒤 뺀다. 서버가 다시 뜨면 비고, 그때 남은 RUNNING 줄은
     * 기동 정리가 Hermes 에 물어 정한다(ADR-061).
     */
    private final ConcurrentHashMap<Long, RunningDelegation> running = new ConcurrentHashMap<>();

    /**
     * 같은 호출 확인과 동시 한도 세기부터 실행 줄 저장까지를 루트별로 묶는다.
     *
     * <p>서버 하나를 전제로 JVM 안에서 잠근다. 서버가 여러 대가 되면 데이터베이스 잠금으로 옮긴다.
     */
    private final ReentrantLock[] rootLocks = new ReentrantLock[ROOT_LOCK_STRIPES];

    /** 서버 전체에서 동시에 도는 위임의 자리다. 실행을 끝까지 돈 가상 스레드가 돌려준다. */
    private final Semaphore activeDelegations;

    public AgentDelegationService(
            AgentService agents,
            AgentExecutionRepository executions,
            ExecutionDeliveryWriter deliveryWriter,
            ChildExecutionRunner children,
            ConversationRepository conversations,
            DelegationProperties properties,
            TurnCancellation turns,
            HermesRunsClient hermes,
            ApplicationEventPublisher events,
            ProactiveCheckGuard checkGuard,
            Clock clock) {
        this.agents = agents;
        this.executions = executions;
        this.deliveryWriter = deliveryWriter;
        this.children = children;
        this.conversations = conversations;
        this.properties = properties;
        this.turns = turns;
        this.hermes = hermes;
        this.events = events;
        this.checkGuard = checkGuard;
        this.clock = clock;
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
     * {@link #status(CurrentUser, AgentExecution, Long)} 와 같이 읽되, 그 실행이 이 서버에서 돌고 있으면 끝나기를 기다린 뒤 다시 읽는다.
     *
     * <p>먼저 살펴보기 트리에서만 기다린다(ADR-080). 그 밖의 origin 은 {@code wait} 를 받아도 기다리지 않는다. 부모는 맡긴 뒤
     * 기다리지 않고 끝난 결과는 다음 turn 에 전해지기 때문이다(ADR-040).
     *
     * <p>기다리는 시간은 {@code wait} 와 {@link DelegationProperties#statusWaitMax()} 가운데 짧은 쪽이다. 0 이하이면 기다리지
     * 않는다. 이 서버가 돌리지 않는 {@code RUNNING} 실행은 끝나도 알 길이 없어 기다리지 않는다. 처음 읽은 뒤 그 사이 끝나 도는
     * 표시가 없어졌으면 한 번 다시 읽어 끝난 상태를 준다.
     *
     * <p>트랜잭션을 걸지 않는다. 한 트랜잭션 안에서 기다리면 다시 읽어도 같은 영속 컨텍스트의 엔티티가 나오고, 기다리는 동안 DB
     * 연결도 쥔다. {@link #stop} 과 같은 형태다.
     */
    public Optional<AgentExecution> status(CurrentUser user, AgentExecution origin, Long executionId, Duration wait) {
        Optional<AgentExecution> found = status(user, origin, executionId);
        if (found.isEmpty()
                || found.get().status() != ExecutionStatus.RUNNING
                || wait.isZero()
                || wait.isNegative()
                || !checkGuard.isCheckTree(origin)) {
            return found;
        }
        RunningDelegation delegation = running.get(executionId);
        if (delegation == null) {
            return executions.findById(executionId);
        }
        delegation.awaitEnded(wait.compareTo(properties.statusWaitMax()) < 0 ? wait : properties.statusWaitMax());
        return executions.findById(executionId);
    }

    /**
     * 요청자가 물을 수 있는 위임 실행 하나를 멈추고 그 뒤의 상태를 돌려준다. 그 실행이 다시 맡긴 실행은 멈추지 않는다.
     *
     * <p>권한은 {@link #status} 와 같이 {@link #canQuery} 가 정한다. 아니면 없는 실행과 같게 빈 값이다. 이미 끝난 실행은
     * 멈추지 않고 끝난 상태를 그대로 돌려준다.
     *
     * <p>도는 실행이면 중지 표시를 켜고, run 번호가 있으면 Hermes 에 중지를 보낸다. 상태는 그 실행을 돌리는 가상 스레드만
     * 적는다. 그래서 멈추기와 끝나기가 겹치면 먼저 적힌 상태가 남는다. CANCELLED 가 적히기를 {@link #STOP_WAIT} 까지
     * 기다리고, 그 안에 적히지 않으면 RUNNING 인 줄을 그대로 돌려준다. 부르는 쪽은 그것을 중지를 요청한 상태로 읽는다.
     *
     * <p>이 프로세스에 중지 표시가 없는 도는 실행은 서버가 다시 뜬 뒤 기동 정리가 다시 붙어 있는 실행이다(ADR-061).
     * run 번호가 있으면 Hermes 에 중지만 보내고 상태를 기다리지 않는다. 기동 정리가 다시 물을 때 취소로 읽혀 그 줄이
     * CANCELLED 로 적힌다. run 번호가 없거나 보내지 못하면 아무것도 멈추지 않았으므로
     * {@link DelegationStop#stopRequested()} 가 거짓이다.
     */
    public Optional<DelegationStop> stop(CurrentUser user, AgentExecution origin, Long executionId) {
        Optional<AgentExecution> found = status(user, origin, executionId);
        if (found.isEmpty() || found.get().status() != ExecutionStatus.RUNNING) {
            return found.map(execution -> new DelegationStop(execution, false));
        }
        AgentExecution execution = found.get();
        RunningDelegation delegation = running.get(executionId);
        if (delegation == null) {
            boolean sent = execution.hermesRunId() != null && stopDetached(execution);
            return executions.findById(executionId).map(reread -> new DelegationStop(reread, sent));
        }
        String runId = delegation.requestStop();
        if (runId != null) {
            sendStop(delegation, runId);
        }
        delegation.awaitEnded(STOP_WAIT);
        // 중지 표시는 켜졌다. Hermes 에 보내지 못했어도 실행이 끝날 때 CANCELLED 로 적힌다.
        return executions.findById(executionId).map(reread -> new DelegationStop(reread, true));
    }

    /**
     * 끝난 먼저 살펴보기 트리의 위임 결과를 전했다고 적고, 그 트리에서 도는 위임 자식을 멈춘다(ADR-080).
     *
     * <p>전달 표시는 그 루트의 잠금 안에서 한 번 더 적는다. 살펴보기가 끝나기 직전에 위임 판정을 지나 만든 자식 줄까지 표시가
     * 붙는다. 그 뒤 {@code RUNNING} 인 위임 자식마다, 이 서버가 돌리는 것이면 중지 표시를 켜고 run 번호가 있으면 Hermes 에 중지를
     * 보낸다. 이 서버가 돌리지 않는 것은 run 번호가 있으면 Hermes 에 중지만 보낸다. 끝나기를 기다리지 않는다.
     *
     * <p>부르는 쪽이 Control Plane 이라 {@link #canQuery} 를 거치지 않는다. 전달 표시가 실패해도 자식은 멈춘다.
     *
     * @param rootExecutionId 살펴보기 turn 의 실행 줄
     */
    public void stopRunningChildrenOf(Long rootExecutionId) {
        ReentrantLock lock = lockOf(rootExecutionId);
        lock.lock();
        try {
            deliveryWriter.markTreeDelivered(rootExecutionId, clock.instant());
        } catch (RuntimeException ex) {
            log.warn("끝난 살펴보기 트리의 위임 결과를 전했다고 적지 못했다 rootExecutionId={}", rootExecutionId, ex);
        } finally {
            lock.unlock();
        }
        for (AgentExecution child : executions.findByRootExecutionId(rootExecutionId)) {
            if (child.status() != ExecutionStatus.RUNNING || child.delegationKey() == null) {
                continue;
            }
            RunningDelegation delegation = running.get(child.id());
            if (delegation == null) {
                if (child.hermesRunId() != null) {
                    stopDetached(child);
                }
                continue;
            }
            String runId = delegation.requestStop();
            if (runId != null) {
                sendStop(delegation, runId);
            }
        }
    }

    /**
     * 다른 에이전트의 실행을 origin 실행의 자식으로 시작하고, Hermes 제출까지만 기다린다(ADR-017 「{@code agent_delegate} 는
     * 기다리지 않는다」).
     *
     * <p>판정은 이 순서로 한다. 부모의 대화, 깊이, 에이전트, 살펴보기의 맡길 곳, 같은 호출, 살펴보기의 위임 상한, 루트당 동시
     * 한도, 전체 한도, 실행 시작, 제출 대기다. 앞의 넷은 잠그지 않고 기다리지 않는다. 살펴보기의 둘은 먼저 살펴보기 트리에서만
     * 본다({@code docs/backend/proactive-check.md} 의 「읽기 경계」). 같은 호출 확인부터 실행 줄 저장까지는 루트별로 잠가, 세기와 시작 사이에
     * 다른 위임이 끼어들지 못하게 한다. 제출 대기는 잠금 밖에서 한다. 잠금이 제출 대기까지 덮으면 같은 루트의 위임이
     * 모두 한 줄로 늘어선다. 잠금도 {@link DelegationProperties#submitTimeout()} 안에서만 기다리고, 그 안에 잡지 못하거나
     * 잡은 뒤 남은 시간이 없으면 실행을 시작하지 않고 거절한다.
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
            if (ex.code() == ErrorCode.AGENT_DISABLED) {
                return rejected(Failure.AGENT_DISABLED, origin, "꺼진 에이전트다");
            }
            if (ex.code() == ErrorCode.AGENT_NOT_FOUND) {
                return rejected(Failure.AGENT_UNAVAILABLE, origin, "없거나 쓸 수 없는 에이전트다");
            }
            throw ex;
        }
        boolean checkTree = checkGuard.isCheckTree(origin);
        // 다른 에이전트는 셸이나 브라우저를 가질 수 있어 읽기 경계 밖이다. 커넥터 에이전트는 자기 MCP 서버만 갖고 그 호출이
        // 커넥터 판정의 읽기 경계에 걸린다.
        if (checkTree && !(agent.connectorManaged() && Objects.equals(agent.ownerUserId(), user.id()))) {
            return rejected(Failure.CHECK_TARGET, origin, "살펴보기 트리에서 요청자의 커넥터 에이전트가 아닌 곳에 맡기려 했다");
        }

        Long rootId = origin.treeRootId();
        long deadline = System.nanoTime() + properties.submitTimeout().toNanos();
        Handoff handoff;
        ReentrantLock lock = lockOf(rootId);
        // 잠금도 제출 대기 시간 안에서만 기다린다. 못 잡으면 스레드도 줄도 만들지 않아, 같은 키로 다시 불러도 새로 시작한다.
        // 한도에 닿은 것이 아니라 제한 시간이 지난 것이라 BUSY 가 아니라 SUBMIT_FAILED 다.
        if (!tryLock(lock, deadline)) {
            return rejected(Failure.SUBMIT_FAILED, origin, "제한 시간 안에 루트 잠금을 잡지 못했다");
        }
        try {
            Optional<AgentExecution> existing = executions.findByDelegationKey(delegationKey.value());
            if (existing.isPresent()) {
                return sameCall(user, origin, existing.get());
            }
            if (checkTree && !withinCheckLimit(origin, rootId)) {
                return rejected(Failure.CHECK_LIMIT, origin, "살펴보기가 끝났거나 위임 상한에 닿았다");
            }
            if (executions.countByRootExecutionIdAndStatusAndDelegationKeyIsNotNull(rootId, ExecutionStatus.RUNNING)
                    >= properties.maxConcurrentChildren()) {
                return rejected(Failure.TOO_MANY_CHILDREN, origin, "루트당 동시 위임 한도에 닿았다");
            }
            // 남은 시간이 없으면 실행 스레드를 띄우지 않는다. 띄우면 곧바로 포기하게 되고 CANCELLED 줄만 남는다.
            if (deadline - System.nanoTime() <= 0) {
                return rejected(Failure.SUBMIT_FAILED, origin, "실행을 시작하기 전에 제한 시간이 지났다");
            }
            if (!activeDelegations.tryAcquire()) {
                return rejected(Failure.BUSY, origin, "서버 전체 동시 위임 한도에 닿았다");
            }
            handoff = new Handoff();
            Handoff started = handoff;
            try {
                Thread.ofVirtual()
                        .name("agent-delegate-" + rootId)
                        .start(() ->
                                run(user, conversation.get(), origin, agent, task, delegationKey, checkTree, started));
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
            if (handoff.checkEnded()) {
                return rejected(Failure.CHECK_LIMIT, origin, "실행 줄을 만들기 전에 살펴보기가 끝났다");
            }
            if (isUserBusy(handoff.failure())) {
                // 실행 줄을 만들기 전에 사용자 실행 한도에 닿았다(ADR-069). 모델에 가는 코드를 늘리지 않고 BUSY 로 알린다.
                return rejected(Failure.BUSY, origin, "사용자 동시 실행 한도에 닿았다");
            }
            if (handoff.failure() instanceof DataIntegrityViolationException) {
                // 같은 키를 다른 요청이 먼저 저장했다. 트랜잭션 밖에서 그 줄을 다시 읽는다.
                return executions
                        .findByDelegationKey(delegationKey.value())
                        .map(raced -> sameCall(user, origin, raced))
                        .orElseGet(() -> rejected(Failure.SUBMIT_FAILED, origin, "저장에 실패했고 다시 읽은 줄도 없다"));
            }
            return rejected(Failure.SUBMIT_FAILED, origin, "실행 줄을 만들지 못했다");
        }
        AgentExecution execution = handoff.execution();
        boolean settled = handoff.awaitSettled(deadline);
        if (settled && !handoff.submitted()) {
            // 부모는 번호 없이 실패만 받는다. 적지 않으면 번호를 모르는 결과가 부모 대화에 다시 전해진다.
            deliveryWriter.markResultDelivered(execution.id(), clock.instant());
            return rejected(Failure.SUBMIT_FAILED, origin, "제출하기 전에 실행이 끝났다 executionId=" + execution.id());
        }
        // 제한 시간이 지나도 줄이 있으면 번호를 돌려준다. 뒤따르는 결과는 그 줄에 적힌다.
        return DelegationResult.started(execution.id(), ExecutionStatus.RUNNING);
    }

    /**
     * 살펴보기 트리에서 하나 더 맡길 수 있는가. 루트 잠금 안에서 부른다.
     *
     * <p>그 살펴보기가 {@code RUNNING} 이 아니면 맡기지 않는다. 멈추는 동안 이미 나간 {@code agent_delegate} 가 끝날 때의 정리
     * ({@link #stopRunningChildrenOf}) 뒤에 자식 줄을 만들면, 그 결과로 점검 대화에 보통 자동 turn 이 열린다. 맡긴 수는 끝난
     * 자식까지 센다. 한 번의 살펴보기에서 맡긴 총수의 상한이다.
     */
    private boolean withinCheckLimit(AgentExecution origin, Long rootId) {
        return checkRunning(origin)
                && executions.countByRootExecutionIdAndDelegationKeyIsNotNull(rootId) < checkGuard.maxDelegations();
    }

    /** origin 이 속한 살펴보기가 아직 {@code RUNNING} 인가. 살펴보기 트리가 아니면 거짓이다. */
    private boolean checkRunning(AgentExecution origin) {
        return checkGuard
                .checkOf(origin)
                .map(check -> check.status() == CheckStatus.RUNNING)
                .orElse(false);
    }

    /**
     * 가상 스레드에서 실행 하나를 끝까지 돌린다.
     *
     * <p>어떻게 끝나든 전체 한도 자리를 돌려주고 요청 스레드를 깨운다. 실행 줄 저장이 예외를 던진 경로도 같다.
     *
     * <p>실행은 셋 가운데 하나가 참이면 멈춘다. 요청 스레드가 제출 대기를 포기했다, {@code agent_stop} 이 중지 표시를
     * 켰다, 사용자가 루트 turn 을 멈췄다({@link #rootTurnStopped}). run 번호가 붙으면 루트 turn 에 그 run 을 붙여, 사용자가 turn 을 멈출 때 이
     * 실행도 함께 멈추게 한다. turn 이 이미 끝났으면 붙일 곳이 없어 붙지 않는다.
     *
     * <p>살펴보기 트리면 실행을 시작하기 전에 그 살펴보기가 아직 {@code RUNNING} 인지 한 번 더 본다. 루트 잠금 안에서 본 뒤 이
     * 스레드가 뜨기까지 살펴보기가 끝났으면 실행 줄을 만들지 않고 요청 스레드가 {@code CHECK_LIMIT} 로 거절한다.
     *
     * @param checkTree origin 이 먼저 살펴보기 트리 안에 있다
     */
    private void run(
            CurrentUser user,
            Conversation conversation,
            AgentExecution origin,
            Agent agent,
            String task,
            DelegationKey delegationKey,
            boolean checkTree,
            Handoff handoff) {
        Long rootId = origin.treeRootId();
        RunningDelegation delegation = new RunningDelegation(agent.apiBaseUrl(), agent.hermesProfile());
        RuntimeException failure = null;
        try {
            if (checkTree && !checkRunning(origin)) {
                handoff.markCheckEnded();
                return;
            }
            children.delegate(
                    user,
                    conversation,
                    origin,
                    agent,
                    task,
                    delegationKey,
                    execution -> {
                        // 번호를 돌려받은 쪽이 곧바로 멈출 수 있게, 요청 스레드를 깨우기 전에 중지 표시를 등록한다.
                        delegation.bindExecution(execution.id());
                        running.put(execution.id(), delegation);
                        handoff.onRowCreated(execution);
                    },
                    (execution, runId) -> {
                        handoff.markSubmitted();
                        if (delegation.attachRun(runId)) {
                            sendStop(delegation, runId);
                        }
                        turns.trackRun(rootId, agent.apiBaseUrl(), agent.hermesProfile(), runId);
                    },
                    () -> handoff.abandoned() || delegation.stopRequested() || rootTurnStopped(rootId));
        } catch (DataIntegrityViolationException ex) {
            failure = ex;
            log.info("같은 위임 키의 실행 줄이 먼저 저장됐다 originExecutionId={}", origin.id());
        } catch (RuntimeException ex) {
            failure = ex;
            if (isUserBusy(ex)) {
                log.info("사용자 실행 한도에 닿아 위임 실행을 시작하지 않았다 originExecutionId={}", origin.id());
            } else {
                log.warn("위임 실행이 예외로 끝났다 originExecutionId={}", origin.id(), ex);
            }
        } finally {
            Long executionId = delegation.executionId();
            if (executionId != null) {
                running.remove(executionId, delegation);
            }
            String runId = delegation.runId();
            if (runId != null) {
                // 끝난 run 을 turn 중지가 다시 멈추지 않게 뗀다.
                turns.untrackRun(rootId, runId);
            }
            delegation.markEnded();
            activeDelegations.release();
            handoff.markEnded(failure);
            if (executionId != null) {
                publishFinished(conversation, executionId);
            }
        }
    }

    /**
     * 위임 실행이 끝났다고 알린다. 받는 쪽이 부모 대화에 전할지 정한다.
     *
     * <p>받는 쪽의 예외가 이 실행의 정리를 막지 않게, 알리다 난 예외는 경고 로그만 남긴다. 대화 번호도 이 안에서 읽는다.
     * 대화가 없어 난 예외가 실행 스레드의 정리 블록 밖으로 나가지 않게 하기 위해서다.
     */
    private void publishFinished(Conversation conversation, Long executionId) {
        try {
            events.publishEvent(new DelegationFinished(conversation.id(), executionId));
        } catch (RuntimeException ex) {
            log.warn("위임 실행이 끝났다고 알리지 못했다 executionId={}", executionId, ex);
        }
    }

    /** 중지를 보낸다. 보내지 못해도 중지 표시는 켜진 채라, 실행이 끝날 때 CANCELLED 로 적힌다. */
    private void sendStop(RunningDelegation delegation, String runId) {
        try {
            hermes.stop(delegation.apiBaseUrl(), delegation.profileName(), runId);
        } catch (RuntimeException ex) {
            log.warn("위임 실행의 Hermes run 을 멈추지 못했다 executionId={} runId={}", delegation.executionId(), runId, ex);
        }
    }

    /**
     * 이 프로세스에 중지 표시가 없는 실행의 run 에 중지만 보낸다. 보냈으면 참이다.
     *
     * <p>에이전트 행이 없으면 보낼 주소가 없다. 로그만 남기고 보내지 못한 것으로 답한다.
     */
    private boolean stopDetached(AgentExecution execution) {
        Optional<Agent> found = agents.findById(execution.agentId());
        if (found.isEmpty()) {
            log.warn(
                    "에이전트 행이 없어 끊긴 위임 실행의 Hermes run 을 멈추지 못했다 executionId={} agentId={}",
                    execution.id(),
                    execution.agentId());
            return false;
        }
        try {
            hermes.stop(found.get().apiBaseUrl(), execution.profileName(), execution.hermesRunId());
            return true;
        } catch (RuntimeException ex) {
            log.warn("끊긴 위임 실행의 Hermes run 을 멈추지 못했다 executionId={}", execution.id(), ex);
            return false;
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
            parentId = executions
                    .findById(parentId)
                    .map(AgentExecution::parentExecutionId)
                    .orElse(null);
        }
        return depth;
    }

    private ReentrantLock lockOf(Long rootId) {
        return rootLocks[Math.floorMod(rootId.hashCode(), ROOT_LOCK_STRIPES)];
    }

    /** 마감 시각까지 잠금을 기다린다. 끊기면 잡지 못한 것과 같게 보고 끊긴 표시는 되살린다. */
    private static boolean tryLock(ReentrantLock lock, long deadline) {
        try {
            return lock.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 사용자가 루트 turn 을 멈췄는지 본다. 흐름의 하위 실행과 같은 판정이다.
     *
     * <p>중지 버튼을 누른 것만으로는 멈춘 것으로 보지 않는다. Hermes 가 중지를 받아들여 확정된 뒤에만 참이다. 중지를 보내지
     * 못하면 turn 은 원래대로 돌아가는데, 그 사이에 이 실행이 끝나면 멀쩡한 답을 CANCELLED 로 적게 된다. 루트 turn 에 붙은
     * run 이 하나도 없어 Hermes 에 보낼 곳이 없는 중지는 확정을 기다리지 않고 참이다.
     */
    private boolean rootTurnStopped(Long rootId) {
        return turns.isStopConfirmed(rootId) || turns.shouldStopBeforeSubmit(rootId);
    }

    private static boolean isUserBusy(RuntimeException failure) {
        return failure instanceof ApiException api && api.code() == ErrorCode.USER_BUSY;
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
     * 대화의 turn 마다 루트 실행이 새로 생기므로, 앞 turn 에서 맡긴 실행을 다음 turn 에서 물으려면 트리가 아니라 대화로
     * 견줘야 한다. origin 실행에 대화가 없으면 같은 실행 트리로 견준다. 같은 사용자의 다른 대화는 물을 수 없다.
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
}
