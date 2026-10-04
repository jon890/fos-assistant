package com.bifos.assistant.task.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.KnownFlows;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.TurnHandle;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.user.application.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code QUEUED} 발화를 작업 주인의 대화 turn 으로 연다(ADR-076).
 *
 * <p>규칙은 {@code docs/backend/task.md} 의 「시작」 이 갖는다. 상태 판정과 건너뛰기와 대화 준비는 줄을 잠그고 다시 읽는 짧은
 * 트랜잭션이다. turn 은 그 밖에서 가상 스레드로 돌고, 위임 결과를 전하는 자동 turn 과 같은 모양이다
 * ({@code DelegationWakeService}). 예약 turn 을 위한 판정이나 허락을 따로 두지 않는다. 쓰기 도구는 사람이 보낸 turn 과 같은
 * 승인 경로를 탄다.
 *
 * <p>잠금은 {@link TurnCancellation} 의 메모리 맵처럼 서버 하나를 전제로 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRunStarter {

    private final TaskRunRepository runs;
    private final TaskRepository tasks;
    private final AppUserRepository users;
    private final SignInRevocation revocation;
    private final AgentService agents;
    private final KnownFlows flows;
    private final ConversationRepository conversations;
    private final ChatService chat;
    private final TurnCancellation turns;
    private final ConversationEventHub hub;
    private final TaskNotices notices;
    private final TaskProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * {@code QUEUED} 줄을 예정 시각 순으로 열어 본다. 한 줄의 실패는 그 줄에서 멈추고 다음 줄로 간다.
     *
     * @return turn 을 연 줄 수
     */
    public int startQueued(Instant now) {
        List<TaskRun> queued = runs.findByStatusOrderByScheduledForAscIdAsc(TaskRunStatus.QUEUED);
        int started = 0;
        for (TaskRun run : queued) {
            try {
                if (start(run.id(), now)) {
                    started++;
                }
            } catch (RuntimeException ex) {
                log.warn("예약 작업의 발화를 열지 못했다 taskRunId={}", run.id(), ex);
            }
        }
        return started;
    }

    /**
     * 줄 하나를 판정하고, 열 수 있으면 잠금을 얻어 {@code RUNNING} 으로 바꾸고 turn 을 띄운다.
     *
     * @return turn 을 띄웠으면 true
     */
    private boolean start(Long runId, Instant now) {
        Prepared prepared = transactions.execute(status -> prepare(runId, now));
        if (prepared == null) {
            return false;
        }
        TurnHandle handle;
        try {
            handle = turns.open(prepared.owner().id(), prepared.conversationId());
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.CONVERSATION_BUSY || ex.code() == ErrorCode.USER_BUSY) {
                // QUEUED 로 두고 다음 tick 에 다시 본다. 만든 대화는 줄에 남아 다시 쓴다.
                return false;
            }
            throw ex;
        }
        boolean marked;
        try {
            marked = Boolean.TRUE.equals(transactions.execute(status -> markRunning(runId, now)));
        } catch (RuntimeException ex) {
            log.warn("예약 작업의 발화를 도는 중으로 적지 못했다 taskRunId={}", runId, ex);
            marked = false;
        }
        if (!marked) {
            turns.close(handle);
            return false;
        }
        try {
            Thread.ofVirtual().name("task-run-" + runId).start(() -> runTurn(runId, prepared, handle));
        } catch (RuntimeException | Error ex) {
            log.warn("예약 작업의 turn 스레드를 띄우지 못했다 taskRunId={}", runId, ex);
            finishSafely(runId, null, false, true);
            turns.close(handle);
            return false;
        }
        return true;
    }

    /**
     * 줄을 잠그고 다시 읽어 「시작」 표를 차례로 본다. 열 수 없으면 건너뛰고 알린다. 열 수 있으면 대화를 준비해 줄에 적는다.
     *
     * @return 열 준비가 됐으면 그 값, 아니면 null
     */
    private Prepared prepare(Long runId, Instant now) {
        TaskRun run = runs.findByIdForUpdate(runId).orElse(null);
        if (run == null || run.status() != TaskRunStatus.QUEUED) {
            return null;
        }
        Task task = tasks.findById(run.taskId()).orElseThrow();
        if (task.state() != TaskState.ACTIVE) {
            skip(task, run, TaskRunReason.PAUSED, now);
            return null;
        }
        Optional<AppUser> found = users.findById(task.ownerUserId());
        if (found.isEmpty() || revocation.revoked(found.get().email())) {
            skip(task, run, TaskRunReason.OWNER_REVOKED, now);
            return null;
        }
        AppUser user = found.get();
        CurrentUser owner = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        if (!agentUsable(owner, task)) {
            skip(task, run, TaskRunReason.AGENT_UNAVAILABLE, now);
            return null;
        }
        if (!now.isBefore(run.createdAt().plus(properties.startTimeout()))) {
            skip(task, run, TaskRunReason.BUSY, now);
            return null;
        }
        Long conversationId = conversationFor(task, run, now);
        return new Prepared(owner, conversationId, task.title(), task.instruction());
    }

    /** 주인이 그 작업의 에이전트로 대화를 시작할 수 있고 흐름이 붙지 않았는가. 지웠거나 껐으면 false 다. */
    private boolean agentUsable(CurrentUser owner, Task task) {
        Optional<Agent> agent = agents.findById(task.agentId());
        if (agent.isEmpty() || agent.get().isDeleted()) {
            return false;
        }
        try {
            agents.requireStartable(owner, agent.get().code());
        } catch (ApiException ex) {
            return false;
        }
        return !flows.known(agent.get().flow());
    }

    /**
     * 이 줄이 결과를 남길 대화를 정하고 줄에 적는다.
     *
     * <p>줄이 이미 대화를 가졌고 그 대화가 지워지지 않았으면 그것을 다시 쓴다. 앞 tick 에서 잠금을 얻지 못한 줄이다. 기다리는 동안
     * 사용자가 그 대화를 지웠으면 처음 준비하는 줄처럼 다시 정한다. {@code SINGLE} 은 작업의 대화가 지워지지 않았고 에이전트가
     * 같으면 그것을, 아니면 새로 만들어 작업에도 적는다. 대화의 에이전트는 바뀌지 않기 때문이다.
     */
    private Long conversationFor(Task task, TaskRun run, Instant now) {
        if (run.conversationId() != null
                && conversations
                        .findByIdAndUserIdAndDeletedAtIsNull(run.conversationId(), task.ownerUserId())
                        .isPresent()) {
            return run.conversationId();
        }
        Long conversationId;
        if (task.conversationMode() == ConversationMode.SINGLE) {
            conversationId = reusableConversation(task).orElseGet(() -> {
                Long created = chat.startForTask(task.ownerUserId(), task.agentId(), task.title(), task.id())
                        .id();
                // 잠그지 않고 읽은 task 를 고치면 그 줄 전체를 읽은 때의 값으로 덮는다. 대화 칸만 적는다.
                tasks.useConversation(task.id(), created, now.truncatedTo(ChronoUnit.MICROS));
                return created;
            });
        } else {
            conversationId = chat.startForTask(task.ownerUserId(), task.agentId(), task.title(), task.id())
                    .id();
        }
        run.useConversation(conversationId);
        return conversationId;
    }

    private Optional<Long> reusableConversation(Task task) {
        if (task.conversationId() == null) {
            return Optional.empty();
        }
        return conversations
                .findByIdAndUserIdAndDeletedAtIsNull(task.conversationId(), task.ownerUserId())
                .filter(conversation -> conversation.agentId().equals(task.agentId()))
                .map(Conversation::id);
    }

    /** 다시 읽어 아직 {@code QUEUED} 일 때만 {@code RUNNING} 으로 바꾼다. 두 tick 이 같은 줄을 함께 열지 않는다. */
    private boolean markRunning(Long runId, Instant now) {
        TaskRun run = runs.findByIdForUpdate(runId).orElse(null);
        if (run == null || run.status() != TaskRunStatus.QUEUED) {
            return false;
        }
        run.start(now);
        return true;
    }

    /** turn 을 돌리고 결과를 적은 뒤 잠금을 푼다. 결과를 잠금을 풀기 전에 적는다. */
    private void runTurn(Long runId, Prepared prepared, TurnHandle handle) {
        Long conversationId = prepared.conversationId();
        try {
            ChatTurn turn = chat.runScheduledTurn(
                    prepared.owner(),
                    conversationId,
                    handle,
                    "예약 작업 「" + prepared.title() + "」 을 시작했어요",
                    prepared.instruction(),
                    event -> hub.publish(conversationId, event));
            finishSafely(runId, turn.executionId(), turn.cancelled(), false);
        } catch (ApiException ex) {
            log.warn("예약 작업의 turn 이 실패했다 taskRunId={} code={}", runId, ex.code(), ex);
            hub.publish(conversationId, ChatEvent.error(ex.code().name(), ex.getMessage()));
            finishSafely(runId, null, false, true);
        } catch (RuntimeException ex) {
            log.error("예약 작업의 turn 이 예외로 끝났다 taskRunId={}", runId, ex);
            hub.publish(conversationId, ChatEvent.error("INTERNAL_ERROR", "internal error"));
            finishSafely(runId, null, false, true);
        } finally {
            turns.close(handle);
        }
    }

    /**
     * 도는 줄의 결과를 적고 알린다. 적지 못해도 예외를 올리지 않는다. 그 줄은 {@code RUNNING} 으로 남아 다음 기동의 정리가
     * 닫는다.
     */
    private void finishSafely(Long runId, Long executionId, boolean cancelled, boolean failed) {
        try {
            transactions.executeWithoutResult(status -> {
                TaskRun run = runs.findByIdForUpdate(runId).orElse(null);
                if (run == null || run.status() != TaskRunStatus.RUNNING) {
                    return;
                }
                Instant now = clock.instant();
                if (failed) {
                    run.fail(TaskRunReason.FAILED, now);
                } else if (cancelled) {
                    run.cancel(executionId, now);
                } else {
                    run.succeed(executionId, now);
                }
                notices.announce(tasks.findById(run.taskId()).orElseThrow(), run);
            });
        } catch (RuntimeException ex) {
            log.warn("예약 작업의 발화 결과를 적지 못했다 taskRunId={}", runId, ex);
        }
    }

    private void skip(Task task, TaskRun run, TaskRunReason reason, Instant now) {
        run.skip(reason, now);
        notices.announce(task, run);
    }

    /** 열 준비가 끝난 줄이다. turn 스레드가 받는다. */
    private record Prepared(CurrentUser owner, Long conversationId, String title, String instruction) {}
}
