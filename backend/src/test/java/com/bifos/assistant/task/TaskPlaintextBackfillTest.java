package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.crypto.application.dto.BackfillDirection;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.application.TaskPlaintextBackfill;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.task.application.dto.ScheduleInput;
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

/** 키를 넣기 전에 저장한 예약 작업의 제목과 지시문을 조금씩 암호화하고, 되돌릴 때 평문으로 되돌리는지 확인한다(ADR-20261008 / data-encryption). */
@BackendIntegrationTest
class TaskPlaintextBackfillTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final String TITLE = "평문-표식-6621 월급 정리";
    private static final String INSTRUCTION = "평문-표식-6622 지난달 월급 입금 내역을 정리해 줘";
    private static final int ALL = 100_000;

    @Autowired
    TestClock clock;

    @Autowired
    TaskPlaintextBackfill backfill;

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
        // 같은 데이터베이스를 쓰는 다른 검사가 남긴 평문 줄이 먼저 잡히지 않게 비운다
        backfill.run(BackfillDirection.SEAL, ALL);
        owner = member();
        agent = agentOf(owner);
    }


    @AfterEach
    void tearDown() {
        clean();
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("평문 작업의 제목과 지시문이 암호화되고 다시 돌려도 바뀌지 않으며 읽으면 평문이다")
    void sealsPlainTaskAndStaysStable() {
        Long id = plainTask(TITLE, INSTRUCTION);

        assertThat(backfill.run(BackfillDirection.SEAL, ALL)).isEqualTo(1);

        Map<String, Object> row = rowOf(id);
        assertThat((String)row.get("TITLE")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat((String)row.get("INSTRUCTION")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(row.get("TITLE_KEY_ID")).isNotNull();
        assertThat(row.get("INSTRUCTION_KEY_ID")).isNotNull();
        Task reloaded = tasks.findById(id).orElseThrow();
        assertThat(reloaded.title()).isEqualTo(TITLE);
        assertThat(reloaded.instruction()).isEqualTo(INSTRUCTION);

        assertThat(backfill.run(BackfillDirection.SEAL, ALL)).isZero();
        assertThat(rowOf(id).get("TITLE")).isEqualTo(row.get("TITLE"));
    }


    @Test
    @DisplayName("한 번에 고치는 줄 수는 배치 크기까지다")
    void sealsOnlyBatchSizeRowsPerRun() {
        Long first = plainTask(TITLE, INSTRUCTION);
        Long second = plainTask("평문-표식-6623 둘", INSTRUCTION);
        Long third = plainTask("평문-표식-6624 셋", INSTRUCTION);

        assertThat(backfill.run(BackfillDirection.SEAL, 2)).isEqualTo(2);
        assertThat(rowOf(third).get("TITLE_KEY_ID")).isNull();

        assertThat(backfill.run(BackfillDirection.SEAL, 2)).isEqualTo(1);
        assertThat(rowOf(first).get("TITLE_KEY_ID")).isNotNull();
        assertThat(rowOf(second).get("TITLE_KEY_ID")).isNotNull();
        assertThat(rowOf(third).get("TITLE_KEY_ID")).isNotNull();
    }


    @Test
    @DisplayName("점검 작업은 지시문이 null 인 채 제목만 암호화된다")
    void checkTaskSealsTitleOnly() {
        Task check = Task.check(owner.id(), agent.id(), "평문-표식-6625 매일 살펴보기");
        check.reschedule(TriggerType.CRON, "0 9 * * *", null, ZoneId.of("Asia/Seoul"), NOW.plusSeconds(3_600));
        check.changeMissedPolicy(MissedPolicy.SKIP);
        Long id = tasks.save(check).id();
        jdbc.update("UPDATE task SET title = ?, title_key_id = NULL WHERE id = ?", "평문-표식-6625 매일 살펴보기", id);

        backfill.run(BackfillDirection.SEAL, ALL);

        Map<String, Object> row = rowOf(id);
        assertThat((String)row.get("TITLE")).startsWith("v1.");
        assertThat(row.get("INSTRUCTION")).isNull();
        assertThat(row.get("INSTRUCTION_KEY_ID")).isNull();
        assertThat(tasks.findById(id).orElseThrow().title()).isEqualTo("평문-표식-6625 매일 살펴보기");
    }


    @Test
    @DisplayName("UNSEAL 이면 암호문 작업이 평문과 빈 key 칸으로 돌아가고 OFF 이면 돌지 않는다")
    void unsealRestoresPlaintext() {
        Long id = plainTask(TITLE, INSTRUCTION);
        backfill.run(BackfillDirection.SEAL, ALL);
        assertThat(rowOf(id).get("TITLE_KEY_ID")).isNotNull();

        assertThat(backfill.run(BackfillDirection.OFF, ALL)).isZero();
        assertThat(rowOf(id).get("TITLE_KEY_ID")).isNotNull();

        backfill.run(BackfillDirection.UNSEAL, ALL);

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("TITLE")).isEqualTo(TITLE);
        assertThat(row.get("INSTRUCTION")).isEqualTo(INSTRUCTION);
        assertThat(row.get("TITLE_KEY_ID")).isNull();
        assertThat(row.get("INSTRUCTION_KEY_ID")).isNull();
        assertThat(tasks.findById(id).orElseThrow().title()).isEqualTo(TITLE);

        // 다른 검사가 남긴 줄도 암호문으로 되돌려 둔다
        backfill.run(BackfillDirection.SEAL, ALL);
    }


    /** 서비스로 만든 작업을 키를 넣기 전에 저장한 옛 줄처럼 평문과 빈 key 칸으로 돌린다. */
    private Long plainTask(String title, String instruction) {
        Long id = service.create(owner, input("임시 제목", "임시 지시")).task().id();
        jdbc.update(
            "UPDATE task SET title = ?, title_key_id = NULL, instruction = ?, instruction_key_id = NULL WHERE id = ?",
            title, instruction, id);
        return id;
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
        String email = "task-backfill-" + UUID.randomUUID() + "@example.com";
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
