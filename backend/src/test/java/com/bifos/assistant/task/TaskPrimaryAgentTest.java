package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.dto.TaskToolInput;
import com.bifos.assistant.mcp.application.dto.TaskToolResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.TaskAgentRequests;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.task.application.dto.ScheduleInput;
import com.bifos.assistant.task.application.dto.TaskInput;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.testsupport.TestPrimaryAgents;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 예약 작업은 만들거나 내용을 고치면 요청자의 기본 비서로 돈다(docs/features/assistant-hub/spec.md 의 REQ7, DECISION8, DECISION23).
 *
 * <p>배포 전부터 있던 전문 에이전트의 작업은 이제 만들 수 없어, 비서로 만든 작업의 에이전트 칸을 바꿔 흉내 낸다.
 */
@BackendIntegrationTest
class TaskPrimaryAgentTest {

    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

    @Autowired
    TestPrimaryAgents primaries;

    @Autowired
    TestClock clock;

    @Autowired
    TaskService taskService;

    @Autowired
    TaskAgentRequests requests;

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
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser owner;
    private Agent primary;
    private Agent shopper;


    @BeforeEach
    void setUp() {
        clock.set(NOW);
        clean();
        owner = member();
        primary = primaries.make(owner, agentOf(owner));
        shopper = agentOf(owner);
    }


    @AfterEach
    void tearDown() {
        clean();
    }


    @Test
    @DisplayName("화면 API 로 만든 작업은 기본 비서로 돈다")
    void createsWithPrimary() {
        Task task = taskService.create(owner, input("아침 날씨")).task();

        assertThat(task.agentId()).isEqualTo(primary.id());
    }


    @Test
    @DisplayName("전문 에이전트의 맡긴 실행이 task_ 도구로 만든 작업도 기본 비서로 돈다")
    void toolCreatesWithPrimary() {
        Conversation conversation = conversations.save(Conversation.startedBy(owner.id(), "대화", primary.id(), NOW));
        AgentExecution root = executions.save(execution(primary, conversation).build());
        AgentExecution child = executions.save(execution(shopper, conversation).parentExecutionId(root.id()).build());

        TaskToolResult result = requests.create(owner, child.id(), new TaskToolInput("특가 확인", "특가를 알린다", "0 9 * * *", null, null));

        assertThat(result.error()).isFalse();
        assertThat(only().agentId()).isEqualTo(primary.id());
    }


    @Test
    @DisplayName("전문 에이전트의 작업은 멈추고 다시 켜도 그대로이고, 화면이나 task_ 도구로 고치면 기본 비서로 바뀐다")
    void editSwitchesToPrimaryButPauseKeeps() {
        UUID screen = specialistTask("화면으로 고칠 작업");
        UUID tool = specialistTask("도구로 고칠 작업");

        taskService.pause(owner, screen);
        taskService.resume(owner, screen);
        assertThat(agentOf(screen)).isEqualTo(shopper.id());

        taskService.update(owner, screen, input("화면으로 고친 작업"));
        TaskToolResult edited = requests.update(owner, tool.toString(), new TaskToolInput(null, null, "0 8 * * *", null, null));

        assertThat(edited.error()).isFalse();
        assertThat(agentOf(screen)).isEqualTo(primary.id());
        assertThat(agentOf(tool)).isEqualTo(primary.id());
    }


    @Test
    @DisplayName("기본 비서가 없으면 작업을 만들지 않는다")
    void rejectsWithoutPrimary() {
        CurrentUser stranger = member();
        agentOf(stranger);

        assertThatThrownBy(() -> taskService.create(stranger, input("아침 날씨")))
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(ErrorCode.AGENT_NOT_FOUND);
    }


    /** 배포 전부터 있던 전문 에이전트의 작업이다. 비서로 만든 뒤 에이전트 칸만 바꾼다. */
    private UUID specialistTask(String title) {
        Task task = taskService.create(owner, input(title)).task();
        jdbc.update("update task set agent_id = ? where id = ?", shopper.id(), task.id());
        return task.publicId();
    }


    private Long agentOf(UUID taskId) {
        return taskService.get(owner, taskId).task().agentId();
    }


    private Task only() {
        return tasks.findAll().stream().filter(task -> owner.id().equals(task.ownerUserId())).findFirst().orElseThrow();
    }


    private static TaskInput input(String title) {
        return new TaskInput(
            title,
            title + " 를 확인해 알린다",
            new ScheduleInput(TriggerType.CRON, "0 9 * * *", null, "Asia/Seoul"),
            ConversationMode.NEW_PER_RUN,
            null,
            null,
            null);
    }


    private void clean() {
        runs.deleteAll();
        tasks.deleteAll();
    }


    private CurrentUser member() {
        String email = "task-primary-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private Agent agentOf(CurrentUser user) {
        String code = "tp-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(code, code, code, "http://agent-runtime.test/p/" + code, CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, user.id()));
    }


    private AgentExecution.Builder execution(Agent agent, Conversation conversation) {
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
}
