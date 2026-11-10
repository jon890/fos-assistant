package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.task.application.dto.ScheduleInput;
import com.bifos.assistant.task.application.dto.TaskDetail;
import com.bifos.assistant.task.application.dto.TaskInput;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 예약 작업의 제목과 지시문이 저장할 때 암호화되고, 옮기거나 주인을 바꾼 암호문은 풀리지 않는지 확인한다(ADR-20261008 / data-encryption). */
@BackendIntegrationTest
class TaskContentEncryptionTest {

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String TITLE = "평문-표식-6612 월급 정리";
    private static final String INSTRUCTION = "평문-표식-6613 지난달 월급 입금 내역을 정리해 줘";

    @Autowired
    TestClock clock;

    @Autowired
    TaskService service;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskRunRepository runs;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    DataKeyService dataKeys;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser owner;
    private Agent agent;


    @BeforeEach
    void setUp() {
        clock.set(NOW);
        clean();
        owner = member();
        agent = agentOf(owner);
    }


    @AfterEach
    void tearDown() {
        clean();
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("만든 작업의 제목과 지시문은 데이터베이스에 암호문이고 응답과 읽어 온 작업은 평문이다")
    void createdTaskIsCiphertextInDatabaseAndPlaintextWhenRead() {
        TaskDetail created = service.create(owner, input(TITLE, INSTRUCTION));

        Map<String, Object> row = rowOf(created.task().id());
        assertThat((String)row.get("TITLE")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat((String)row.get("INSTRUCTION")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(row.get("TITLE_KEY_ID")).isNotNull();
        assertThat(row.get("INSTRUCTION_KEY_ID")).isNotNull();
        assertThat(created.task().title()).isEqualTo(TITLE);
        assertThat(created.task().instruction()).isEqualTo(INSTRUCTION);
        Task reloaded = tasks.findById(created.task().id()).orElseThrow();
        assertThat(reloaded.title()).isEqualTo(TITLE);
        assertThat(reloaded.instruction()).isEqualTo(INSTRUCTION);
    }


    @Test
    @DisplayName("작업을 고치면 새 제목과 지시문이 암호문으로 남고 읽으면 평문이다")
    void editedTaskIsCiphertextAgain() {
        Long id = service.create(owner, input(TITLE, INSTRUCTION)).task().id();
        UUID publicId = tasks.findById(id).orElseThrow().publicId();

        TaskDetail updated = service.update(owner, publicId, input("평문-표식-6614 고친 이름", "평문-표식-6615 고친 지시"));

        Map<String, Object> row = rowOf(id);
        assertThat((String)row.get("TITLE")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat((String)row.get("INSTRUCTION")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(updated.task().title()).isEqualTo("평문-표식-6614 고친 이름");
        Task reloaded = tasks.findById(id).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("평문-표식-6614 고친 이름");
        assertThat(reloaded.instruction()).isEqualTo("평문-표식-6615 고친 지시");
    }


    @Test
    @DisplayName("점검 작업의 지시문은 null 이고 옆 칸도 null 이며 제목은 암호문이다")
    void checkTaskHasNoInstruction() {
        Task check = Task.check(owner.id(), agent.id(), "매일 살펴보기");
        check.reschedule(TriggerType.CRON, "0 9 * * *", null, ZoneId.of("Asia/Seoul"), NOW.plusSeconds(3_600));
        check.changeMissedPolicy(MissedPolicy.SKIP);
        Task saved = tasks.save(check);

        Map<String, Object> row = rowOf(saved.id());
        assertThat(row.get("INSTRUCTION")).isNull();
        assertThat(row.get("INSTRUCTION_KEY_ID")).isNull();
        assertThat((String)row.get("TITLE")).startsWith("v1.");
        Task reloaded = tasks.findById(saved.id()).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("매일 살펴보기");
        assertThat(reloaded.instruction()).isNull();
    }


    @Test
    @DisplayName("두 칸의 암호문을 맞바꾸거나 주인을 바꾸면 읽을 수 없는 글이다")
    void movedCiphertextOrChangedOwnerIsUnreadable() {
        Long first = service.create(owner, input(TITLE, INSTRUCTION)).task().id();
        Long second = service.create(owner, input("평문-표식-6616 둘째", "평문-표식-6617 둘째 지시")).task().id();
        Map<String, Object> firstRow = rowOf(first);
        jdbc.update("UPDATE task SET title = ?, title_key_id = ? WHERE id = ?",
            firstRow.get("TITLE"), firstRow.get("TITLE_KEY_ID"), second);
        // 제목 암호문을 지시문 칸으로 옮긴 경우도 같은 줄의 다른 칸이라 풀리지 않는다.
        jdbc.update("UPDATE task SET instruction = ?, instruction_key_id = ? WHERE id = ?",
            firstRow.get("TITLE"), firstRow.get("TITLE_KEY_ID"), first);
        dataKeys.forgetCachedKeys();

        Task moved = tasks.findById(second).orElseThrow();
        assertThat(moved.title()).isEqualTo(Task.UNREADABLE_CONTENT);
        assertThat(tasks.findById(first).orElseThrow().instruction()).isEqualTo(Task.UNREADABLE_CONTENT);
        assertThat(tasks.findById(first).orElseThrow().title()).isEqualTo(TITLE);

        jdbc.update("UPDATE task SET owner_user_id = ? WHERE id = ?", member().id(), first);
        dataKeys.forgetCachedKeys();
        assertThat(tasks.findById(first).orElseThrow().title()).isEqualTo(Task.UNREADABLE_CONTENT);
    }


    @Test
    @DisplayName("key 칸이 비어 있는 옛 줄은 평문으로 읽힌다")
    void legacyRowWithoutKeyReadsAsPlaintext() {
        Long id = service.create(owner, input(TITLE, INSTRUCTION)).task().id();

        jdbc.update("UPDATE task SET title = ?, title_key_id = NULL, instruction = ?, instruction_key_id = NULL WHERE id = ?",
            "옛 제목", "옛 지시", id);

        Task reloaded = tasks.findById(id).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("옛 제목");
        assertThat(reloaded.instruction()).isEqualTo("옛 지시");
    }


    private Map<String, Object> rowOf(Long taskId) {
        return jdbc.queryForMap(
            "SELECT title, title_key_id, instruction, instruction_key_id FROM task WHERE id = ?", taskId);
    }


    private TaskInput input(String title, String instruction) {
        return new TaskInput(
            title,
            agent.code(),
            instruction,
            new ScheduleInput(TriggerType.CRON, "0 9 1 * *", null, null),
            null,
            null,
            null,
            null);
    }


    private CurrentUser member() {
        String email = "task-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private Agent agentOf(CurrentUser owner) {
        String code = "task-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
            code,
            code,
            code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
    }


    private void clean() {
        runs.deleteAll();
        tasks.deleteAll();
    }
}
