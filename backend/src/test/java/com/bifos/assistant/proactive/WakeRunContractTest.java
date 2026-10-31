package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.dto.RunEvent;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.hermes.domain.ToolDetailScope;
import com.bifos.assistant.hermes.infra.HermesRunEventStream;
import com.bifos.assistant.proactive.check.application.CheckFeedback;
import com.bifos.assistant.proactive.check.application.ProactiveCheckRecovery;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.check.domain.type.CheckStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.library.application.SkillCommandCatalog;
import com.bifos.assistant.task.application.ProactiveScheduleService;
import com.bifos.assistant.task.application.TaskFiring;
import com.bifos.assistant.task.application.TaskRunRecovery;
import com.bifos.assistant.task.application.TaskRunStarter;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.usage.execution.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 매일 깨우기 한 번의 실행 계약이다(ADR-20261101 / proactive-suggestion). 판단을 어떻게 하든 바뀌지 않아야 하는 Control Plane 의 경계만 본다.
 *
 * <ul>
 * <li>예산(도구 호출 상한)에 닿으면 멈추고 멈춘 까닭과 실행 줄을 남긴다
 * <li>Hermes 가 실패해도 실패한 깨우기와 실행 줄을 남긴다
 * <li>챙길 것이 없는 깨우기도 성공한 실행으로 남고 대화와 알림에는 아무것도 남기지 않는다
 * <li>서버가 다시 떠도 같은 예정 시각의 깨우기는 한 번만 돈다
 * </ul>
 *
 * <p>모든 값은 합성이다.
 */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
@OverrideProperties({"assistant.proactive-check.max-tool-calls=" + WakeRunContractTest.MAX_TOOL_CALLS})
class WakeRunContractTest extends ProactiveLoopTestSupport {

    static final int MAX_TOOL_CALLS = 2;

    /** 서울 08:00 이다. 매일 09:00 깨우기의 다음 예정 시각은 {@link #DUE} 다. */
    private static final Instant BEFORE_DUE = Instant.parse("2026-11-01T23:00:00Z");
    private static final Instant DUE = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant TICK = DUE.plusSeconds(30);

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    HermesRunEventStream eventStream;

    @Autowired
    SkillCommandCatalog skills;

    @Autowired
    ProactiveScheduleService schedules;

    @Autowired
    TaskFiring firing;

    @Autowired
    TaskRunStarter starter;

    @Autowired
    TaskRunRecovery taskRecovery;

    @Autowired
    ExecutionDeliveryWriter deliveryWriter;

    @Autowired
    ApplicationEventPublisher events;

    private final List<Long> scheduledUsers = new ArrayList<>();


    @BeforeEach
    void enableSkill() {
        doReturn(Set.of("proactive-check")).when(skills).enabledNames(any(Agent.class));
    }


    @AfterEach
    void removeSchedules() {
        for (Long userId : scheduledUsers) {
            jdbc.update("DELETE FROM notification WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM task_run WHERE owner_user_id = ?", userId);
            jdbc.update("DELETE FROM task WHERE owner_user_id = ?", userId);
        }
        scheduledUsers.clear();
    }


    @Test
    @DisplayName("도구 호출이 예산을 넘은 깨우기는 멈추고 CHECK_TOOL_LIMIT 와 끝난 실행 줄을 남기며 답을 남기지 않는다")
    void wakeOverBudgetStopsAndRecordsReason() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        CountDownLatch stopReceived = new CountDownLatch(1);
        stub().onStop(runId -> stopReceived.countDown());
        stub().beforeAwait(() -> await(stopReceived));
        stub().willAnswer(command -> result("cancelled", "<fos-check-result>{\"version\":3"));
        streams(toolEvents(MAX_TOOL_CALLS + 1));

        ProactiveCheck check = startAndWait(user, agent);

        assertThat(check.status()).isEqualTo(CheckStatus.STOPPED);
        assertThat(check.errorCode()).isEqualTo("CHECK_TOOL_LIMIT");
        assertThat(check.finishedAt()).isNotNull();
        AgentExecution root = executions.findById(check.rootExecutionId()).orElseThrow();
        assertThat(root.status()).as("실행 줄은 멈춘 대로 남는다").isNotEqualTo(ExecutionStatus.RUNNING);
        assertThat(root.finishedAt()).isNotNull();
        assertThat(stub().stopped()).as("Hermes 에 간 중지").contains(root.hermesRunId());
        assertThat(assistantMessages(check)).isZero();
    }


    @Test
    @DisplayName("Hermes 가 실패한 깨우기는 FAILED 와 오류 코드, 실패한 실행 줄을 남긴다")
    void failedWakeIsRecorded() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "합성 장애"));

        ProactiveCheck check = startAndWait(user, agent);

        assertThat(check.status()).isEqualTo(CheckStatus.FAILED);
        assertThat(check.errorCode()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE.name());
        assertThat(check.finishedAt()).isNotNull();
        AgentExecution root = executions.findById(check.rootExecutionId()).orElseThrow();
        assertThat(root.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(assistantMessages(check)).isZero();
    }


    @Test
    @DisplayName("챙길 것이 없는 깨우기는 성공한 실행으로 남고 대화 메시지와 알림, 발견과 문제 후보를 남기지 않는다")
    void wakeWithNoSuggestionIsRecordedSilently() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);

        Long checkId = wake(user, agent, nothingNewOutput());

        ProactiveCheck check = checks.findById(checkId).orElseThrow();
        assertThat(check.status()).isEqualTo(CheckStatus.SUCCEEDED);
        assertThat(check.outcome()).isEqualTo(CheckOutcome.NOTHING_NEW);
        AgentExecution root = executions.findById(check.rootExecutionId()).orElseThrow();
        assertThat(root.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(count("SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?", check.conversationId()))
            .as("대화에 남은 줄")
            .isZero();
        assertThat(count("SELECT COUNT(*) FROM proactive_check_finding WHERE check_id = ?", checkId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM proactive_check_problem WHERE check_id = ?", checkId)).isZero();
        assertThat(notificationCount(user)).isZero();
    }


    @Test
    @DisplayName("깨우기가 끝난 뒤 서버가 다시 떠 같은 예정 시각으로 다시 tick 해도 발화와 살펴보기와 Hermes 실행은 하나다")
    void restartDoesNotFireSameWakeTwice() throws InterruptedException {
        CurrentUser user = scheduledUser();
        Agent agent = newAgent(user);
        enableDailyWake(user, agent);
        stub().willAnswer(command -> result("completed", nothingNewOutput()));

        assertThat(firing.fireDue(TICK)).as("첫 tick 의 발화").isEqualTo(1);
        assertThat(starter.startQueued(TICK)).as("첫 tick 이 연 발화").isEqualTo(1);
        awaitIdle();
        restart();
        assertThat(firing.fireDue(TICK)).as("다시 뜬 뒤 같은 시각의 발화").isZero();
        assertThat(starter.startQueued(TICK)).as("다시 뜬 뒤 연 발화").isZero();
        awaitIdle();

        assertThat(count("SELECT COUNT(*) FROM task_run WHERE owner_user_id = ?", user.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM proactive_check WHERE user_id = ?", user.id())).isEqualTo(1);
        assertThat(stub().received()).hasSize(1);
    }


    @Test
    @DisplayName("발화만 만들고 열기 전에 서버가 내려가면 다시 뜬 뒤 다음 tick 이 한 번만 연다")
    void queuedWakeOpensOnceAfterRestart() throws InterruptedException {
        CurrentUser user = scheduledUser();
        Agent agent = newAgent(user);
        enableDailyWake(user, agent);
        stub().willAnswer(command -> result("completed", nothingNewOutput()));

        assertThat(firing.fireDue(TICK)).isEqualTo(1);
        restart();
        assertThat(firing.fireDue(TICK)).isZero();
        assertThat(starter.startQueued(TICK)).isEqualTo(1);
        awaitIdle();
        assertThat(starter.startQueued(TICK.plusSeconds(60))).isZero();
        awaitIdle();

        assertThat(count("SELECT COUNT(*) FROM task_run WHERE owner_user_id = ?", user.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM proactive_check WHERE user_id = ?", user.id())).isEqualTo(1);
        assertThat(stub().received()).hasSize(1);
    }


    private CurrentUser scheduledUser() {
        CurrentUser user = newUser();
        scheduledUsers.add(user.id());
        return user;
    }


    /** 서울 09:00 매일 깨우기를 켠다. 다음 예정 시각이 {@link #DUE} 다. */
    private void enableDailyWake(CurrentUser user, Agent agent) {
        clock.set(BEFORE_DUE);
        assertThat(schedules.update(user, agent.code(), true, "09:00", "Asia/Seoul").enabled()).isTrue();
        clock.set(TICK);
    }


    /** 서버가 다시 뜰 때 도는 두 기동 정리다. 살펴보기 복구는 프로세스마다 한 번만 돌므로 같은 빈으로 새로 만들어 시작한다. */
    private void restart() {
        new ProactiveCheckRecovery(checks, deliveryWriter, events, mock(CheckFeedback.class), clock).start();
        taskRecovery.onReady();
    }


    private ProactiveCheck startAndWait(CurrentUser user, Agent agent) {
        Long[] id = new Long[1];
        checkService.startScheduled(user, agent.code(), saved -> id[0] = saved.id());
        try {
            awaitIdle();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return checks.findById(id[0]).orElseThrow();
    }


    private long assistantMessages(ProactiveCheck check) {
        return count(
            "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ? AND role = ?",
            check.conversationId(),
            MessageRole.ASSISTANT.name());
    }


    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }


    private void streams(RunEvent... events) {
        doAnswer(invocation -> {
            Consumer<RunEvent> onEvent = invocation.getArgument(3);
            for (RunEvent event : events) {
                onEvent.accept(event);
            }
            return null;
        })
            .when(eventStream)
            .open(any(), any(), any(), any(), any(), any(ToolDetailScope.class));
    }


    private static RunEvent[] toolEvents(int count) {
        List<RunEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new RunEvent("tool.started", null, "web_search", "query-" + i, null, null));
        }
        return events.toArray(RunEvent[]::new);
    }


    private static HermesRunResult result(String status, String output) {
        return HermesRunResult.of("run-" + UUID.randomUUID(), null, status, output, "model", "provider", TokenUsage.empty());
    }


    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }
}
