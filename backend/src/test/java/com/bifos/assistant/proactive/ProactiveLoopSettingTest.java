package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.proactive.loop.application.ProactiveLoopSettingService;
import com.bifos.assistant.proactive.loop.application.dto.LoopScheduleState;
import com.bifos.assistant.proactive.loop.application.dto.LoopSettingView;
import com.bifos.assistant.proactive.loop.application.port.LoopScheduleSettings;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 설치가 매일 루프를 연 상태에서 사용자 설정의 저장, 쉬기 범위, 권한을 본다. */
@BackendIntegrationTest
@OverrideProperties("assistant.proactive-loop.enabled=true")
class ProactiveLoopSettingTest {

    private static final Instant NOW = Instant.parse("2026-03-02T00:00:00Z");
    private static final CurrentUser OWNER = new CurrentUser(962_001L, "owner@example.com", "사용자A", 1L, UserRole.MEMBER);
    private static final CurrentUser OTHER = new CurrentUser(962_002L, "other@example.com", "사용자B", 1L, UserRole.MEMBER);

    @Autowired
    ProactiveLoopSettingService service;

    @Autowired
    LoopScheduleSettings schedules;

    @Autowired
    AgentRepository agents;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private Agent agent;
    private Long sharedAgentId;


    @BeforeEach
    void setUp() {
        clock.set(NOW);
        agent = agents.save(Agent.of(
            "loop-" + UUID.randomUUID(),
            "커리어",
            "loop-profile-" + UUID.randomUUID(),
            "http://agent-runtime.test/p/loop",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            OWNER.id()));
    }


    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM proactive_loop_setting WHERE agent_id = ?", agent.id());
        jdbc.update("DELETE FROM task WHERE agent_id = ?", agent.id());
        jdbc.update("DELETE FROM agent WHERE id = ?", agent.id());
        if (sharedAgentId != null) {
            jdbc.update("DELETE FROM task WHERE agent_id = ?", sharedAgentId);
            jdbc.update("DELETE FROM agent WHERE id = ?", sharedAgentId);
            sharedAgentId = null;
        }
    }


    @Test
    @DisplayName("설정 줄이 없으면 꺼짐이고 설치가 루프를 열었으므로 켤 수 있다")
    void returnsDisabledWithoutRow() {
        LoopSettingView view = service.get(OWNER, agent.code());

        assertThat(view).isEqualTo(new LoopSettingView(true, false, null));
        assertThat(schedules.find(OWNER.id(), agent.id())).isEmpty();
    }


    @Test
    @DisplayName("켜고 쉬기를 저장하면 다시 읽어도 같은 값이고 쉬기 시각이 지나면 비어 보인다")
    void savesEnabledAndSnooze() {
        Instant snooze = NOW.plus(Duration.ofDays(2));

        LoopSettingView saved = service.update(OWNER, agent.code(), true, snooze);

        assertThat(saved).isEqualTo(new LoopSettingView(true, true, snooze));
        assertThat(service.get(OWNER, agent.code())).isEqualTo(new LoopSettingView(true, true, snooze));

        clock.set(snooze);
        assertThat(service.get(OWNER, agent.code())).isEqualTo(new LoopSettingView(true, true, null));
    }


    @Test
    @DisplayName("같은 사용자와 에이전트의 설정은 하나이고, 끄면 같은 CHECK 작업을 멈춘다")
    void keepsOneSettingPerUserAndAgent() {
        service.update(OWNER, agent.code(), true, null);
        service.update(OWNER, agent.code(), true, NOW.plus(Duration.ofDays(1)));
        Long taskId = checkTaskIds(OWNER).getFirst();

        LoopSettingView view = service.update(OWNER, agent.code(), false, null);

        assertThat(view).isEqualTo(new LoopSettingView(true, false, null));
        assertThat(checkTaskIds(OWNER)).as("CHECK 작업은 하나이고 같은 줄이 바뀐다").containsExactly(taskId);
        assertThat(taskState(taskId)).isEqualTo(TaskState.PAUSED);
        assertThat(service.update(OWNER, agent.code(), true, null))
            .isEqualTo(new LoopSettingView(true, true, null));
        assertThat(taskState(taskId)).as("다시 켜면 같은 작업이 켜진다").isEqualTo(TaskState.ACTIVE);
    }


    @Test
    @DisplayName("같은 공유 에이전트를 쓰는 두 사용자의 설정은 서로 바뀌지 않는다")
    void keepsPerUserSettingOnSharedAgent() {
        Agent shared = agents.save(Agent.of(
            "loop-shared-" + UUID.randomUUID(),
            "커리어",
            "loop-shared-profile-" + UUID.randomUUID(),
            "http://agent-runtime.test/p/loop-shared",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.GROUP,
            OWNER.id()));
        sharedAgentId = shared.id();
        Instant snooze = NOW.plus(Duration.ofDays(2));
        service.update(OWNER, shared.code(), true, snooze);
        service.update(OTHER, shared.code(), false, null);

        assertThat(service.get(OWNER, shared.code())).isEqualTo(new LoopSettingView(true, true, snooze));
        assertThat(service.get(OTHER, shared.code())).isEqualTo(new LoopSettingView(true, false, null));
        assertThat(checkTaskIds(OWNER, shared.id())).hasSize(1);

        service.update(OTHER, shared.code(), true, null);
        service.update(OWNER, shared.code(), false, null);

        assertThat(service.get(OTHER, shared.code())).isEqualTo(new LoopSettingView(true, true, null));
        assertThat(service.get(OWNER, shared.code())).isEqualTo(new LoopSettingView(true, false, null));
    }


    @Test
    @DisplayName("옛 루프 동의만 있고 CHECK 작업이 꺼진 사용자는 설정이 꺼져 있다")
    void legacyConsentAloneDoesNotEnable() {
        jdbc.update(
            "INSERT INTO proactive_loop_setting (user_id, agent_id, enabled, snoozed_until, updated_at)"
                + " VALUES (?, ?, TRUE, NULL, CURRENT_TIMESTAMP(6))",
            OWNER.id(),
            agent.id());

        assertThat(service.get(OWNER, agent.code())).isEqualTo(new LoopSettingView(true, false, null));

        service.update(OWNER, agent.code(), false, null);

        assertThat(service.get(OWNER, agent.code())).isEqualTo(new LoopSettingView(true, false, null));
    }


    @Test
    @DisplayName("쉬기는 지금부터 30일까지 받고 31일 뒤는 VALIDATION_FAILED 로 거절해 저장하지 않는다")
    void limitsSnoozeToThirtyDays() {
        Instant limit = NOW.plus(Duration.ofDays(30));

        assertThat(service.update(OWNER, agent.code(), true, limit).snoozedUntil())
            .isEqualTo(limit);

        assertThatThrownBy(() -> service.update(OWNER, agent.code(), false, NOW.plus(Duration.ofDays(31))))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(schedules.find(OWNER.id(), agent.id()).orElseThrow())
            .isEqualTo(new LoopScheduleState(true, limit));
    }


    @Test
    @DisplayName("지난 쉬기 시각은 비운 것으로 저장한다")
    void storesPastSnoozeAsEmpty() {
        LoopSettingView view = service.update(OWNER, agent.code(), true, NOW.minus(Duration.ofMinutes(1)));

        assertThat(view).isEqualTo(new LoopSettingView(true, true, null));
        assertThat(schedules.find(OWNER.id(), agent.id()).orElseThrow().snoozedUntil()).isNull();
    }


    @Test
    @DisplayName("다른 사용자의 비공개 에이전트는 읽기도 끄기도 켜기도 AGENT_NOT_FOUND 이고 줄을 남기지 않는다")
    void rejectsUnreadableAgent() {
        assertThatThrownBy(() -> service.get(OTHER, agent.code()))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        assertThatThrownBy(() -> service.update(OTHER, agent.code(), false, null))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        assertThatThrownBy(() -> service.update(OTHER, agent.code(), true, null))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        assertThat(schedules.find(OTHER.id(), agent.id())).isEmpty();
    }


    private List<Long> checkTaskIds(CurrentUser user) {
        return checkTaskIds(user, agent.id());
    }


    private List<Long> checkTaskIds(CurrentUser user, Long agentId) {
        return jdbc.queryForList(
            "SELECT id FROM task WHERE owner_user_id = ? AND agent_id = ? AND kind = 'CHECK' ORDER BY id",
            Long.class,
            user.id(),
            agentId);
    }


    private TaskState taskState(Long taskId) {
        return TaskState.valueOf(jdbc.queryForObject("SELECT state FROM task WHERE id = ?", String.class, taskId));
    }
}
