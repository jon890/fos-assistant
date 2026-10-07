package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.task.presentation.TaskController;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 예약 작업 경로의 응답 모양과 본문 검사를 본다. 계약은 {@code docs/backend/task.md} 의 「API」 다. */
@BackendIntegrationTest
class TaskControllerTest {

    private static final Instant BASE = Instant.parse("2026-10-04T00:00:00Z");

    @Autowired
    TaskService service;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskTriggerRepository triggers;

    @Autowired
    TaskRunRepository runs;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private Agent agent;

    @BeforeEach
    void setUp() {
        clean();
        String email = "task-api-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, "작업 주인", 1L, UserRole.MEMBER, BASE));
        when(currentUser.require())
                .thenReturn(new CurrentUser(user.id(), user.email(), user.displayName(), 1L, UserRole.MEMBER));
        String code = "task-api-" + UUID.randomUUID().toString().substring(0, 8);
        agent = agents.save(Agent.of(
                code,
                "커리어 비서",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                BASE));
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(currentUser, service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("만들기는 작업 칸과 시각과 기본값을 돌려준다")
    void createReturnsTaskView() throws Exception {
        JsonNode view = body(send(post("/api/v1/tasks"), request("  매달 정리  ", cronSchedule("0 9 1 * *"))), 200);

        assertThat(UUID.fromString(view.path("id").asString())).isNotNull();
        assertThat(view.path("title").asString()).isEqualTo("매달 정리");
        assertThat(view.path("agentCode").asString()).isEqualTo(agent.code());
        assertThat(view.path("agentName").asString()).isEqualTo("커리어 비서");
        assertThat(view.path("instruction").asString()).isEqualTo("지난달 기록을 보고 정리해 줘");
        assertThat(view.path("state").asString()).isEqualTo("ACTIVE");
        assertThat(view.path("schedule").path("type").asString()).isEqualTo("CRON");
        assertThat(view.path("schedule").path("cron").asString()).isEqualTo("0 9 1 * *");
        assertThat(view.path("schedule").path("timeZone").asString()).isEqualTo("Asia/Seoul");
        assertThat(view.path("schedule").path("fireAt").isNull())
                .as("CRON 의 fireAt: %s", view)
                .isTrue();
        assertThat(view.path("nextFireAt").isNull()).as("nextFireAt: %s", view).isFalse();
        assertThat(view.path("lastFiredAt").isNull())
                .as("lastFiredAt: %s", view)
                .isTrue();
        assertThat(view.path("conversationMode").asString()).isEqualTo("NEW_PER_RUN");
        assertThat(view.path("missedPolicy").asString()).isEqualTo("RUN_ONCE");
        assertThat(view.path("notify").asString()).isEqualTo("ALWAYS");
        assertThat(view.path("createdAt").isNull()).as("createdAt: %s", view).isFalse();
    }

    @Test
    @DisplayName("한 번 도는 작업의 fireAt 은 그 작업의 시간대로 바꾼 시간대 없는 시각이다")
    void onceScheduleViewUsesTaskZone() throws Exception {
        Map<String, Object> schedule = Map.of("type", "ONCE", "fireAt", "2099-11-01T09:00", "timeZone", "Asia/Seoul");

        JsonNode view = body(send(post("/api/v1/tasks"), request("한 번", schedule)), 200);

        assertThat(view.path("schedule").path("type").asString()).isEqualTo("ONCE");
        assertThat(view.path("schedule").path("fireAt").asString()).startsWith("2099-11-01T09:00");
        assertThat(view.path("schedule").path("cron").isNull()).isTrue();
        assertThat(Instant.parse(view.path("nextFireAt").asString())).isEqualTo(Instant.parse("2099-11-01T00:00:00Z"));
    }

    @Test
    @DisplayName("목록, 고치기, 멈추기, 다시 켜기, 지우기(204) 가 차례로 응답한다")
    void listUpdatePauseResumeArchive() throws Exception {
        String first = body(send(post("/api/v1/tasks"), request("첫째", cronSchedule("0 9 1 * *"))), 200)
                .path("id")
                .asString();
        String second = body(send(post("/api/v1/tasks"), request("둘째", cronSchedule("0 9 * * 1"))), 200)
                .path("id")
                .asString();

        JsonNode list = body(mvc.perform(get("/api/v1/tasks")).andReturn().getResponse(), 200);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("id").asString()).as("만든 순서의 역순").isEqualTo(second);
        assertThat(list.get(1).path("id").asString()).isEqualTo(first);

        JsonNode updated =
                body(send(put("/api/v1/tasks/{id}", first), request("고친 첫째", cronSchedule("0 9 1 * *"))), 200);
        assertThat(updated.path("title").asString()).isEqualTo("고친 첫째");

        JsonNode paused = body(
                mvc.perform(post("/api/v1/tasks/{id}/pause", first)).andReturn().getResponse(), 200);
        assertThat(paused.path("state").asString()).isEqualTo("PAUSED");
        JsonNode resumed = body(
                mvc.perform(post("/api/v1/tasks/{id}/resume", first))
                        .andReturn()
                        .getResponse(),
                200);
        assertThat(resumed.path("state").asString()).isEqualTo("ACTIVE");

        MockHttpServletResponse deleted =
                mvc.perform(delete("/api/v1/tasks/{id}", first)).andReturn().getResponse();
        assertThat(deleted.getStatus()).isEqualTo(204);
        JsonNode gone =
                body(mvc.perform(get("/api/v1/tasks/{id}", first)).andReturn().getResponse(), 404);
        assertThat(gone.path("code").asString()).isEqualTo("TASK_NOT_FOUND");
        assertThat(body(mvc.perform(get("/api/v1/tasks")).andReturn().getResponse(), 200))
                .hasSize(1);
    }

    @Test
    @DisplayName("발화 기록은 예정 시각의 역순이고 까닭은 enum 이름 그대로다")
    void runsReturnsShapeInScheduledDescOrder() throws Exception {
        String id = body(send(post("/api/v1/tasks"), request("기록", cronSchedule("0 9 * * *"))), 200)
                .path("id")
                .asString();
        Task task = tasks.findByPublicIdAndOwnerUserIdAndKind(
                        UUID.fromString(id), currentUser.require().id(), TaskKind.TURN)
                .orElseThrow();
        TaskTrigger trigger = triggers.findByTaskId(task.id()).orElseThrow();
        Instant older = Instant.parse("2026-10-02T00:00:00Z");
        Instant newer = Instant.parse("2026-10-03T00:00:00Z");
        TaskRun skipped = runs.save(
                TaskRun.skipped(task.id(), trigger.id(), task.ownerUserId(), older, TaskRunReason.MISSED, older));
        TaskRun queued = runs.save(TaskRun.queued(task.id(), trigger.id(), task.ownerUserId(), newer, newer));

        JsonNode list =
                body(mvc.perform(get("/api/v1/tasks/{id}/runs", id)).andReturn().getResponse(), 200);

        assertThat(list).hasSize(2);
        JsonNode top = list.get(0);
        assertThat(top.path("id").asString()).isEqualTo(queued.publicId().toString());
        assertThat(Instant.parse(top.path("scheduledFor").asString())).isEqualTo(newer);
        assertThat(top.path("status").asString()).isEqualTo("QUEUED");
        assertThat(top.path("reason").isNull()).as("QUEUED 의 reason: %s", top).isTrue();
        assertThat(top.path("conversationId").isNull()).isTrue();
        assertThat(top.path("startedAt").isNull()).isTrue();
        assertThat(top.path("finishedAt").isNull()).isTrue();
        JsonNode bottom = list.get(1);
        assertThat(bottom.path("id").asString()).isEqualTo(skipped.publicId().toString());
        assertThat(bottom.path("status").asString()).isEqualTo("SKIPPED");
        assertThat(bottom.path("reason").asString()).isEqualTo("MISSED");
        assertThat(Instant.parse(bottom.path("finishedAt").asString())).isEqualTo(older);

        JsonNode limited = body(
                mvc.perform(get("/api/v1/tasks/{id}/runs", id).param("limit", "1"))
                        .andReturn()
                        .getResponse(),
                200);
        assertThat(limited).hasSize(1);
    }

    @Test
    @DisplayName("빈 이름, 시각 없음, 8000자를 넘는 지시는 400 VALIDATION_FAILED 다")
    void bodyValidationFailuresAreValidationFailed() throws Exception {
        Map<String, Object> noSchedule = Map.of("title", "이름", "agentCode", agent.code(), "instruction", "지시");
        Map<String, Object> longInstruction = request("이름", cronSchedule("0 9 * * *"));
        longInstruction.put("instruction", "가".repeat(8001));

        for (Map<String, Object> bad :
                List.of(request("   ", cronSchedule("0 9 * * *")), noSchedule, longInstruction)) {
            JsonNode error = body(send(post("/api/v1/tasks"), bad), 400);
            assertThat(error.path("code").asString()).as("본문: %s", bad.keySet()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(tasks.count()).isZero();
    }

    @Test
    @DisplayName("CRON 인데 cron 이 비면 400 TASK_SCHEDULE_INVALID 다")
    void cronWithoutExpressionIsScheduleInvalid() throws Exception {
        JsonNode error = body(send(post("/api/v1/tasks"), request("이름", Map.of("type", "CRON"))), 400);

        assertThat(error.path("code").asString()).isEqualTo("TASK_SCHEDULE_INVALID");
    }

    @Test
    @DisplayName("100자를 넘는 cron 은 400 TASK_SCHEDULE_INVALID 다")
    void rejectCronOverMaximumOnCreate() throws Exception {
        String cron = "0 9 1 * " + "1,".repeat(50) + "1";

        JsonNode error = body(send(post("/api/v1/tasks"), request("이름", cronSchedule(cron))), 400);

        assertThat(error.path("code").asString()).isEqualTo("TASK_SCHEDULE_INVALID");
        assertThat(tasks.count()).isZero();
        assertThat(triggers.count()).isZero();
    }

    @Test
    @DisplayName("고치기도 100자를 넘는 cron 을 거절한다")
    void rejectCronOverMaximumOnUpdate() throws Exception {
        String original = "0 9 1 * *";
        String id = body(send(post("/api/v1/tasks"), request("이름", cronSchedule(original))), 200)
                .path("id")
                .asString();
        String cron = "0 9 1 * " + "1,".repeat(50) + "1";

        JsonNode error = body(send(put("/api/v1/tasks/{id}", id), request("고친 이름", cronSchedule(cron))), 400);

        assertThat(error.path("code").asString()).isEqualTo("TASK_SCHEDULE_INVALID");
        assertThat(triggers.findAll())
                .singleElement()
                .extracting(TaskTrigger::cronExpr)
                .isEqualTo(original);
        assertThat(tasks.findAll()).singleElement().extracting(Task::title).isEqualTo("이름");
    }

    @Test
    @DisplayName("앞뒤 공백을 뗀 100자 cron 은 길이로 거절하지 않는다")
    void acceptMaximumCronAfterStrippingWhitespace() throws Exception {
        String cron = "00 9 1 * " + "1,".repeat(45) + "1";
        assertThat(cron).hasSize(TaskTrigger.CRON_MAX);

        JsonNode view = body(send(post("/api/v1/tasks"), request("이름", cronSchedule("  " + cron + "  "))), 200);

        assertThat(view.path("schedule").path("cron").asString()).isEqualTo(cron);
        assertThat(triggers.findAll())
                .singleElement()
                .extracting(TaskTrigger::cronExpr)
                .isEqualTo(cron);
    }

    @Test
    @DisplayName("형식이 틀린 taskId 는 400 VALIDATION_FAILED 다")
    void malformedTaskIdIsValidationFailed() throws Exception {
        JsonNode error = body(
                mvc.perform(get("/api/v1/tasks/{id}", "not-a-uuid")).andReturn().getResponse(), 400);

        assertThat(error.path("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    private Map<String, Object> request(String title, Map<String, Object> schedule) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("agentCode", agent.code());
        body.put("instruction", "지난달 기록을 보고 정리해 줘");
        body.put("schedule", schedule);
        return body;
    }

    private static Map<String, Object> cronSchedule(String cron) {
        return Map.of("type", "CRON", "cron", cron);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder builder, Map<String, Object> body)
            throws Exception {
        return mvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse();
    }

    private JsonNode body(MockHttpServletResponse response, int status) throws Exception {
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("응답: %s", text).isEqualTo(status);
        return json.readTree(text);
    }

    private void clean() {
        runs.deleteAll();
        triggers.deleteAll();
        tasks.deleteAll();
    }
}
