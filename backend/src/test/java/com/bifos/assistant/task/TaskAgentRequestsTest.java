package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.testsupport.TestPrimaryAgents;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.domain.type.GrantPeriod;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.mcp.application.dto.TaskToolInput;
import com.bifos.assistant.mcp.application.dto.TaskToolResult;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.TaskAgentRequests;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 에이전트의 예약 작업 도구가 요청자의 작업을 만들고 고치고 멈추고, 지우기는 승인 카드 뒤에서만 하는지 실제 DB 로 본다(스펙
 * service-concepts REQ7-REQ10, REQ17, REQ18). 계약은 {@code docs/features/service-concepts/design.md} 의 「PR2 예약 작업 도구」 다.
 */
@BackendIntegrationTest
class TaskAgentRequestsTest {

    /** 2026-11-20 서울 9시다. */
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

    @Autowired
    TestPrimaryAgents primaries;

    @Autowired
    TestClock clock;

    @Autowired
    TaskAgentRequests requests;

    @Autowired
    ConnectorActionService actionService;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskRunRepository runs;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;


    @BeforeEach
    void setUp() {
        clock.set(NOW);
        clean();
    }


    @AfterEach
    void tearDown() {
        clean();
    }


    @Test
    @DisplayName("대화에서 만든 작업은 그 대화의 에이전트로 돌고 모델 단계가 비며 결과가 그 대화에 쌓인다")
    void createsWithConversationDefaults() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner);
        Conversation conversation = conversationOf(owner, agent);
        AgentExecution origin = rootOf(owner, agent, conversation);

        TaskToolResult result = requests.create(owner, origin.id(), input("아침 날씨", "0 7 * * *", null));

        assertThat(result.error()).isFalse();
        assertThat(result.body()).containsEntry("status", "created");
        Map<?, ?> summary = (Map<?, ?>)result.body().get("task");
        Task task = tasks.findByOwnerUserIdAndKindAndStateNotOrderByIdDesc(owner.id(), TaskKind.TURN, TaskState.ARCHIVED).getFirst();
        assertThat(task.agentId()).isEqualTo(agent.id());
        assertThat(task.modelTier()).isNull();
        assertThat(task.conversationMode()).isEqualTo(ConversationMode.SINGLE);
        assertThat(task.conversationId()).isEqualTo(conversation.id());
        assertThat(task.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(task.notifyPolicy()).isEqualTo(NotifyPolicy.ALWAYS);
        assertThat(summary.get("link")).isEqualTo("/tasks/" + task.publicId());
        assertThat(summary.get("next_run")).isEqualTo("2026-11-21 07:00 (Asia/Seoul)");
    }


    @Test
    @DisplayName("맡긴 실행이 만든 작업은 기본 비서로 돌고 매번 새 대화다")
    void delegatedRunCreatesPerRunTask() {
        CurrentUser owner = member();
        Agent parentAgent = agentOf(owner);
        Agent childAgent = agentOf(owner);
        Conversation conversation = conversationOf(owner, parentAgent);
        AgentExecution root = rootOf(owner, parentAgent, conversation);
        AgentExecution child = executions.save(execution(owner, childAgent, conversation).parentExecutionId(root.id()).build());

        TaskToolResult result = requests.create(owner, child.id(), input("주간 정리", "0 9 * * 1", null));

        assertThat(result.error()).isFalse();
        Task task = only(owner);
        // 작업은 늘 기본 비서로 돈다(스펙 assistant-hub REQ7). 처음 만든 에이전트가 이 사용자의 기본 비서다.
        assertThat(task.agentId()).isEqualTo(parentAgent.id());
        assertThat(task.conversationMode()).isEqualTo(ConversationMode.NEW_PER_RUN);
        assertThat(task.conversationId()).isNull();
    }


    @Test
    @DisplayName("하루 발화 한도를 넘는 주기와 틀린 시각은 만들지 않고 까닭을 돌려준다")
    void refusesTooFrequentAndInvalidSchedules() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner);
        AgentExecution origin = rootOf(owner, agent, conversationOf(owner, agent));

        TaskToolResult frequent = requests.create(owner, origin.id(), input("촘촘한 감시", "*/15 * * * *", null));
        TaskToolResult invalid = requests.create(owner, origin.id(), input("틀린 시각", "*/5 * * * *", null));
        TaskToolResult past = requests.create(owner, origin.id(), new TaskToolInput("지난 시각", "확인한다", null, "2026-11-19T07:00", null));

        assertThat(frequent.error()).isTrue();
        assertThat(frequent.body()).containsEntry("status", "daily_limit");
        assertThat(invalid.body()).containsEntry("status", "invalid_schedule");
        assertThat(past.body()).containsEntry("status", "invalid_schedule");
        assertThat(tasks.countByOwnerUserIdAndKindAndStateNot(owner.id(), TaskKind.TURN, TaskState.ARCHIVED)).isZero();
    }


    @Test
    @DisplayName("고치기는 받은 칸만 바꾸고 모델 단계, 시간대, 대화 방식은 그대로다")
    void updatesOnlyGivenFields() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner);
        AgentExecution origin = rootOf(owner, agent, conversationOf(owner, agent));
        requests.create(owner, origin.id(), input("아침 날씨", "0 7 * * *", null));
        Task created = only(owner);
        created.chooseModelTier(ModelTier.PRO);
        tasks.save(created);

        TaskToolResult result = requests.update(owner, "아침 날씨", new TaskToolInput(null, null, "30 6 * * *", null, "never"));

        assertThat(result.error()).isFalse();
        Task updated = only(owner);
        assertThat(updated.cronExpr()).isEqualTo("30 6 * * *");
        assertThat(updated.notifyPolicy()).isEqualTo(NotifyPolicy.NEVER);
        assertThat(updated.title()).isEqualTo("아침 날씨");
        assertThat(updated.instruction()).isEqualTo("아침 날씨 를 확인해 알린다");
        assertThat(updated.modelTier()).isEqualTo(ModelTier.PRO);
        assertThat(updated.agentId()).isEqualTo(agent.id());
        assertThat(updated.conversationMode()).isEqualTo(ConversationMode.SINGLE);
    }


    @Test
    @DisplayName("이름이 같은 작업이 둘이면 후보만 돌려주고 바꾸지 않으며, 번호로 고르면 멈추고 다시 켠다")
    void ambiguousNamesReturnCandidates() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner);
        AgentExecution origin = rootOf(owner, agent, conversationOf(owner, agent));
        requests.create(owner, origin.id(), input("뉴스", "0 7 * * *", null));
        requests.create(owner, origin.id(), input("뉴스", "0 19 * * *", null));

        TaskToolResult ambiguous = requests.setPaused(owner, "뉴스", true);

        assertThat(ambiguous.error()).isTrue();
        assertThat(ambiguous.body()).containsEntry("status", "ambiguous");
        assertThat((List<?>)ambiguous.body().get("candidates")).hasSize(2);
        assertThat(tasks.findByOwnerUserIdAndKindAndStateNotOrderByIdDesc(owner.id(), TaskKind.TURN, TaskState.ARCHIVED))
            .allSatisfy(task -> assertThat(task.state()).isEqualTo(TaskState.ACTIVE));

        UUID first = tasks.findByOwnerUserIdAndKindAndStateNotOrderByIdDesc(owner.id(), TaskKind.TURN, TaskState.ARCHIVED)
            .getFirst().publicId();
        assertThat(requests.setPaused(owner, first.toString(), true).body()).containsEntry("status", "paused");
        assertThat(stateOf(owner, first)).isEqualTo(TaskState.PAUSED);
        assertThat(requests.setPaused(owner, first.toString(), false).body()).containsEntry("status", "active");
        assertThat(stateOf(owner, first)).isEqualTo(TaskState.ACTIVE);
    }


    @Test
    @DisplayName("다른 사용자의 작업 번호는 없는 작업과 같고, 매일 깨우기는 목록에 없다")
    void othersTasksAndChecksAreInvisible() {
        CurrentUser owner = member();
        CurrentUser other = member();
        Agent agent = agentOf(owner);
        Agent otherAgent = agentOf(other);
        requests.create(other, rootOf(other, otherAgent, conversationOf(other, otherAgent)).id(), input("남의 작업", "0 7 * * *", null));
        UUID othersTask = only(other).publicId();
        Task check = Task.check(owner.id(), agent.id(), "매일 깨우기");
        check.reschedule(TriggerType.CRON, "0 8 * * *", null, ZoneId.of("Asia/Seoul"), null);
        check.changeMissedPolicy(MissedPolicy.SKIP);
        tasks.save(check);

        TaskToolResult paused = requests.setPaused(owner, othersTask.toString(), true);
        TaskToolResult listed = requests.list(owner);

        assertThat(paused.body()).containsEntry("status", "not_found");
        assertThat(only(other).state()).isEqualTo(TaskState.ACTIVE);
        assertThat((List<?>)listed.body().get("tasks")).isEmpty();
    }


    @Test
    @DisplayName("지우기는 작업을 그대로 두고 승인 카드를 띄우며, 승인하면 지우고 알림 줄만 남긴다")
    void deleteNeedsApproval() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner);
        Conversation conversation = conversationOf(owner, agent);
        AgentExecution origin = rootOf(owner, agent, conversation);
        requests.create(owner, origin.id(), input("아침 날씨", "0 7 * * *", null));

        TaskToolResult requested = requests.requestDelete(owner, origin.id(), "아침 날씨");

        assertThat(requested.error()).isFalse();
        assertThat(requested.body()).containsEntry("status", "approval_requested");
        assertThat(only(owner).state()).isEqualTo(TaskState.ACTIVE);
        ConnectorAction card = actions.findAll().getFirst();
        assertThat(ControlPlaneActions.owns(card)).isTrue();
        assertThat(card.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(card.conversationId()).isEqualTo(conversation.id());
        var view = actionService.listForConversation(owner, conversation.id()).getFirst();
        assertThat(view.title()).isEqualTo("예약 작업 지우기");
        assertThat(view.argsJson()).contains("아침 날씨", "2026-11-21 07:00 (Asia/Seoul)");
        assertThat(view.grantAllowed()).isFalse();
        assertThat(view.conversationGrantAllowed()).isFalse();

        var approved = actionService.approve(owner, card.publicId(), null, false);

        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(tasks.countByOwnerUserIdAndKindAndStateNot(owner.id(), TaskKind.TURN, TaskState.ARCHIVED)).isZero();
        assertThat(actionService.undeliveredResults(conversation.id())).isEmpty();
        assertThat(actions.findByPublicId(card.publicId()).orElseThrow().resultDeliveredAt()).isNotNull();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
            .filteredOn(message -> message.role() == MessageRole.SYSTEM)
            .extracting(message -> message.content())
            .containsExactly("「아침 날씨」 예약 작업을 지웠어요");
    }


    @Test
    @DisplayName("다른 사용자는 지우기 카드를 승인하지 못하고, 허락을 붙인 승인은 입력 오류이며 작업은 그대로다")
    void deleteApprovalRejectsOthersAndGrants() {
        CurrentUser owner = member();
        CurrentUser other = member();
        Agent agent = agentOf(owner);
        AgentExecution origin = rootOf(owner, agent, conversationOf(owner, agent));
        requests.create(owner, origin.id(), input("아침 날씨", "0 7 * * *", null));
        requests.requestDelete(owner, origin.id(), "아침 날씨");
        UUID card = actions.findAll().getFirst().publicId();

        assertThatThrownBy(() -> actionService.approve(other, card, null, false))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTOR_ACTION_NOT_FOUND));
        assertThatThrownBy(() -> actionService.approve(owner, card, GrantPeriod.TODAY, false))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> actionService.approve(owner, card, null, true))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(only(owner).state()).isEqualTo(TaskState.ACTIVE);
        assertThat(actions.findByPublicId(card).orElseThrow().status()).isEqualTo(ActionStatus.PENDING);
    }


    @Test
    @DisplayName("요일을 고른 주기도 앞으로 7일의 하루마다 발화 수를 세고, 같은 만들기를 다시 부르면 한 번만 생긴다")
    void countsCalendarDaysAndIgnoresRepeatedCreate() {
        // 금요일 밤 23시 50분(서울)이다. 지금부터 24시간만 세면 평일 15분 주기가 빠져나간다
        clock.set(Instant.parse("2026-11-20T14:50:00Z"));
        CurrentUser owner = member();
        Agent agent = agentOf(owner);
        AgentExecution origin = rootOf(owner, agent, conversationOf(owner, agent));

        TaskToolResult weekdays = requests.create(owner, origin.id(), input("평일 감시", "*/15 * * * 1-5", null));
        TaskToolResult first = requests.create(owner, origin.id(), input("아침 날씨", "0 7 * * *", null));
        TaskToolResult again = requests.create(owner, origin.id(), input(" 아침 날씨 ", "0 7 * * *", null));

        assertThat(weekdays.body()).containsEntry("status", "daily_limit");
        assertThat(first.body()).containsEntry("status", "created");
        assertThat(again.error()).isFalse();
        assertThat(again.body()).containsEntry("status", "exists");
        assertThat(only(owner).title()).isEqualTo("아침 날씨");
    }


    @Test
    @DisplayName("지우기 카드를 거절하면 작업은 그대로다")
    void rejectedDeleteKeepsTask() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner);
        Conversation conversation = conversationOf(owner, agent);
        AgentExecution origin = rootOf(owner, agent, conversation);
        requests.create(owner, origin.id(), input("아침 날씨", "0 7 * * *", null));
        requests.requestDelete(owner, origin.id(), "아침 날씨");
        ConnectorAction card = actions.findAll().getFirst();

        actionService.reject(owner, card.publicId());

        assertThat(only(owner).state()).isEqualTo(TaskState.ACTIVE);
        assertThat(actions.findByPublicId(card.publicId()).orElseThrow().status()).isEqualTo(ActionStatus.REJECTED);
    }


    private static TaskToolInput input(String title, String cron, String notify) {
        return new TaskToolInput(title, title + " 를 확인해 알린다", cron, null, notify);
    }


    private TaskState stateOf(CurrentUser owner, UUID taskId) {
        return tasks.findByPublicIdAndOwnerUserIdAndKind(taskId, owner.id(), TaskKind.TURN).orElseThrow().state();
    }


    private Task only(CurrentUser owner) {
        List<Task> found = tasks.findByOwnerUserIdAndKindAndStateNotOrderByIdDesc(owner.id(), TaskKind.TURN, TaskState.ARCHIVED);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }


    private CurrentUser member() {
        String email = "task-agent-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private Agent agentOf(CurrentUser owner) {
        String code = "ta-" + UUID.randomUUID().toString().substring(0, 8);
        return primaries.ensure(owner, agents.save(Agent.of(code, code, code, "http://agent-runtime.test/p/" + code, CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, owner.id())));
    }


    private Conversation conversationOf(CurrentUser owner, Agent agent) {
        return conversations.save(Conversation.startedBy(owner.id(), "대화", agent.id(), NOW));
    }


    private AgentExecution rootOf(CurrentUser owner, Agent agent, Conversation conversation) {
        return executions.save(execution(owner, agent, conversation).build());
    }


    private static AgentExecution.Builder execution(CurrentUser owner, Agent agent, Conversation conversation) {
        return AgentExecution.builder()
            .userId(owner.id())
            .conversationId(conversation.id())
            .agentId(agent.id())
            .profileName(agent.hermesProfile())
            .costMode(CostMode.SUBSCRIPTION)
            .model("example-model")
            .status(ExecutionStatus.SUCCEEDED)
            .timing(NOW, NOW);
    }


    private void clean() {
        actions.deleteAll();
        runs.deleteAll();
        tasks.deleteAll();
    }
}
