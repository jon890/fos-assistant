package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.DelegationFinished;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/** 위임 실행의 등록, 중지와 종료 정리 및 서버 전체 실행 자리를 함께 소유한다. */
@Slf4j
final class DelegationRuns {

    private final ChildExecutionRunner children;
    private final TurnCancellation turns;
    private final HermesRunsClient hermes;
    private final ApplicationEventPublisher events;
    private final ProactiveCheckGuard checkGuard;

    /**
     * 이 프로세스에서 도는 위임 실행의 중지 표시와 run 참조다. 실행 번호가 열쇠다.
     *
     * <p>실행 줄이 생긴 직후 넣고 그 실행이 끝나 상태를 적은 뒤 뺀다. 서버가 다시 뜨면 비고, 그때 남은 RUNNING 줄은 기동 정리가 Hermes 에 물어 정한다(ADR-061).
     */
    private final ConcurrentHashMap<Long, RunningDelegation> running = new ConcurrentHashMap<>();

    /** 실행을 끝까지 돈 가상 스레드가 돌려주는 서버 전체 위임 자리다. */
    private final DelegationSlots activeDelegations = new DelegationSlots();


    DelegationRuns(
        ChildExecutionRunner children,
        TurnCancellation turns,
        HermesRunsClient hermes,
        ApplicationEventPublisher events,
        ProactiveCheckGuard checkGuard) {
        this.children = children;
        this.turns = turns;
        this.hermes = hermes;
        this.events = events;
        this.checkGuard = checkGuard;
    }


    boolean tryAcquire(int maxActive) {
        return activeDelegations.tryAcquire(maxActive);
    }


    void release() {
        activeDelegations.release();
    }


    RunningDelegation find(Long executionId) {
        return running.get(executionId);
    }


    /** origin 이 속한 살펴보기가 아직 {@code RUNNING} 인가. 살펴보기 트리가 아니면 거짓이다. */
    boolean checkRunning(AgentExecution origin) {
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
    void run(
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
    void sendStop(RunningDelegation delegation, String runId) {
        try {
            hermes.stop(delegation.apiBaseUrl(), delegation.profileName(), runId);
        } catch (RuntimeException ex) {
            log.warn("위임 실행의 Hermes run 을 멈추지 못했다 executionId={} runId={}", delegation.executionId(), runId, ex);
        }
    }


    /**
     * 사용자가 루트 turn 을 멈췄는지 본다.
     *
     * <p>중지 버튼을 누른 것만으로는 멈춘 것으로 보지 않는다. Hermes 가 중지를 받아들여 확정된 뒤에만 참이다. 중지를 보내지
     * 못하면 turn 은 원래대로 돌아가는데, 그 사이에 이 실행이 끝나면 멀쩡한 답을 CANCELLED 로 적게 된다. 루트 turn 에 붙은
     * run 이 하나도 없어 Hermes 에 보낼 곳이 없는 중지는 확정을 기다리지 않고 참이다.
     */
    private boolean rootTurnStopped(Long rootId) {
        return turns.isStopConfirmed(rootId) || turns.shouldStopBeforeSubmit(rootId);
    }


    static boolean isUserBusy(RuntimeException failure) {
        return failure instanceof ApiException api && api.code() == ErrorCode.USER_BUSY;
    }

}
