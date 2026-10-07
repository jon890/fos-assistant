package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** turn 을 중지하고 잠금을 풀기 전에 대기 메시지를 멈춘다. */
@Component
@Slf4j
@RequiredArgsConstructor
class ChatTurnStopper {
    private final AgentService agents;
    private final ExecutionRecorder executions;
    private final AgentExecutionRepository executionRepository;
    private final TurnCancellation turns;
    private final TransactionTemplate transactions;
    private final ChatPendingMessageRepository pendingMessages;
    private final ChatTurnLifecycle chatTurnLifecycle;

    /**
     * 이 turn 이 중지로 끝났다고 적고 그 대화의 대기 줄을 멈춰 둔다.
     *
     * <p>turn 잠금을 풀기 전에 멈춘다. 잠금을 푼 뒤 닫기 리스너에서 멈추면 그 사이 다른 스레드가 아직 멈추지 않은
     * 행으로 turn 을 연다.
     *
     * <p>대기 줄을 멈추다 실패해도 예외를 올리지 않고 경고 로그만 남긴다. 멈춤 때문에 중지한 turn 이 오류로 끝나거나
     * 원래 예외가 가려지면 안 된다.
     */
    void markStoppedAndHoldPending(TurnHandle handle, Long conversationId) {
        turns.markStopped(handle);
        try {
            transactions.executeWithoutResult(status -> pendingMessages.markHeld(conversationId, true));
        } catch (RuntimeException ex) {
            log.warn("중지한 turn 의 대기 줄을 멈춰 두지 못했다 conversationId={}", conversationId, ex);
        }
    }

    /**
     * 중지로 끝난 turn 의 취소 기록을 남긴 뒤 대기 줄을 멈추고 그 turn 을 돌려준다.
     *
     * <p>취소 기록이 먼저다. 대기 줄을 먼저 멈추다 실패하면 실행 줄이 {@code CANCELLED} 로 남지 않는다. 취소 기록이
     * 실패해도 대기 줄은 멈춘다. 둘 다 turn 잠금을 풀기 전이다.
     */
    ChatTurn stoppedTurn(
            TurnHandle handle, PendingTurn pending, HermesRunResult result, ModelChoice choice, Instant startedAt) {
        try {
            return chatTurnLifecycle.recorded(chatTurnLifecycle.cancel(pending, result, choice), startedAt);
        } finally {
            markStoppedTurn(handle, pending);
        }
    }

    /**
     * 멈춘 turn 을 적는다. 상한으로 멈춘 살펴보기 turn 은 대기 줄을 멈추지 않고, 나머지는 {@link #markStoppedAndHoldPending} 과 같다.
     *
     * <p>상한으로 멈춘 것은 사용자가 아니라 Control Plane 이라, 살펴보기 동안 사용자가 보낸 대기 메시지를 그대로 다음 turn 으로
     * 보낸다(ADR-080).
     */
    void markStoppedTurn(TurnHandle handle, PendingTurn pending) {
        if (pending.intent() instanceof TurnIntent.ProactiveCheck proactive
                && !proactive.check().holdPendingOnStop()) {
            turns.markStopped(handle);
            return;
        }
        markStoppedAndHoldPending(handle, pending.conversation().id());
    }

    /**
     * 중지가 확정된 turn 이 예외로 끝날 때 실행 줄을 취소로 남기고 대기 줄을 멈춘다.
     *
     * <p>사용자가 중지를 확정한 실행은 {@code RUNNING} 으로 남지 않는다. 예외가 실행 줄을 이미 {@code FAILED} 로 적은
     * 뒤라면 그대로 둔다. 취소 기록이 실패해도 대기 줄은 멈추고, 어느 쪽 실패도 올리지 않아 원래 예외가 그대로 올라간다.
     */
    void cancelAndHoldIfStopConfirmed(TurnHandle handle, PendingTurn pending, ModelChoice choice) {
        if (!turns.isStopConfirmed(handle)) {
            return;
        }
        try {
            if (pending.execution().status() == ExecutionStatus.RUNNING) {
                chatTurnLifecycle.cancel(pending, null, choice);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "중지한 turn 의 취소를 기록하지 못했다 executionId={}",
                    pending.execution().id(),
                    ex);
        } finally {
            markStoppedTurn(handle, pending);
        }
    }

    /**
     * 중지로 끝난 흐름 turn 의 결과물을 묶은 뒤 대기 줄을 멈춘다.
     *
     * <p>흐름이 취소를 이미 기록했다. 결과물 묶기가 던져도 잠금을 풀기 전에 대기 줄을 멈춘다.
     */
    void recordStoppedFlow(TurnHandle handle, ChatTurn turn, Instant startedAt) {
        try {
            chatTurnLifecycle.recorded(turn, startedAt);
        } finally {
            markStoppedAndHoldPending(handle, turn.conversationId());
        }
    }

    /**
     * 중지가 확정된 흐름 turn 이 예외로 끝날 때 루트 실행 줄을 취소로 남기고 대기 줄을 멈춘다.
     *
     * <p>흐름이 루트 실행을 이미 끝난 상태로 적었으면 그대로 둔다. 실행 줄을 다시 읽어 본다. 흐름이 들고 있는 객체의
     * 상태를 여기서는 알 수 없다. {@code rootExecutionId} 가 null 이면 실행 줄을 만들기 전에 끝난 것이다.
     */
    void cancelFlowAndHoldIfStopConfirmed(TurnHandle handle, Long conversationId, Long rootExecutionId) {
        if (!turns.isStopConfirmed(handle)) {
            return;
        }
        try {
            if (rootExecutionId != null) {
                executionRepository
                        .findById(rootExecutionId)
                        .filter(execution -> execution.status() == ExecutionStatus.RUNNING)
                        .ifPresent(executions::cancel);
            }
        } catch (RuntimeException ex) {
            log.warn("중지한 흐름 turn 의 취소를 기록하지 못했다 executionId={}", rootExecutionId, ex);
        } finally {
            markStoppedAndHoldPending(handle, conversationId);
        }
    }

    void stop(CurrentUser user, Long executionId) {
        TurnHandle handle = turns.find(executionId).orElseGet(() -> {
            AgentExecution execution = executionRepository
                    .findById(executionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "execution not found"));
            if (!execution.userId().equals(user.id())) {
                throw new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "execution not found");
            }
            throw new ApiException(ErrorCode.EXECUTION_NOT_RUNNING, "execution is not running");
        });
        if (!handle.userId().equals(user.id())) {
            throw new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "execution not found");
        }
        turns.cancel(handle);
        boolean hadRuns = turns.hasRuns(handle);
        for (TurnRunRef run : turns.pendingStops(handle)) {
            turns.stopRun(run);
        }
        for (AgentExecution child : executionRepository.findByRootExecutionId(executionId)) {
            if (child.status() != ExecutionStatus.RUNNING || child.hermesRunId() == null) {
                continue;
            }
            Optional<Agent> childAgent = agents.findById(child.agentId());
            if (childAgent.isEmpty()) {
                // 루트 turn 의 중지는 이미 켰다. 에이전트 행이 없는 자식 하나 때문에 오류로 끝내지 않는다.
                log.warn("에이전트 행이 없어 자식 run 을 함께 멈추지 못했다 executionId={} agentId={}", child.id(), child.agentId());
                continue;
            }
            turns.trackRun(executionId, childAgent.get().apiBaseUrl(), child.profileName(), child.hermesRunId());
        }
        boolean firstStopSent = hadRuns || turns.awaitFirstStop(handle);
        if (!firstStopSent && turns.isFinished(handle)) {
            throw new ApiException(ErrorCode.EXECUTION_NOT_RUNNING, "execution is not running");
        }
        // 시도마다의 성패가 아니라 끝난 뒤의 상태로 정한다.
        // 제출과 이 요청이 같은 run 에 함께 보내 한쪽만 받아들여져도 그 run 은 멈췄다.
        // 시도 결과로 정하면 먼저 실패한 쪽 때문에 멈춘 run 을 두고 HERMES_UNAVAILABLE 로 답한다.
        // 멈춘 run 은 끝나면 목록에서 빠지므로 목록이 비었다고 실패로 보지 않는다.
        boolean stopped = firstStopSent || turns.hasStoppedRuns(handle);
        boolean failed = !stopped || !turns.pendingStops(handle).isEmpty();
        if (failed) {
            if (!turns.hasStoppedRuns(handle)) {
                turns.resume(handle);
            }
            throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not stop every Hermes run");
        }
        turns.confirmStop(handle);
    }
}
