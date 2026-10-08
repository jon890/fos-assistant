package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.proactive.application.ProactiveLoopSettingService;
import com.bifos.assistant.proactive.application.model.LoopSettingView;
import com.bifos.assistant.proactive.infra.ProactiveLoopSettingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 설치 설정 기본값(꺼짐)에서 켜기만 막고 끄기와 쉬기는 받는지 본다. */
@BackendIntegrationTest
class ProactiveLoopSettingDisabledTest {

    private static final Instant NOW = Instant.parse("2026-03-02T00:00:00Z");
    private static final CurrentUser OWNER =
            new CurrentUser(963_001L, "owner@example.com", "사용자A", 1L, UserRole.MEMBER);

    @Autowired
    ProactiveLoopSettingService service;

    @Autowired
    ProactiveLoopSettingRepository settings;

    @Autowired
    AgentRepository agents;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private Agent agent;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        agent = agents.save(Agent.of(
                "loop-off-" + UUID.randomUUID(),
                "커리어",
                "loop-off-profile-" + UUID.randomUUID(),
                "http://agent-runtime.test/p/loop-off",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                OWNER.id(),
                NOW));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM proactive_loop_setting WHERE agent_id = ?", agent.id());
        jdbc.update("DELETE FROM agent WHERE id = ?", agent.id());
    }

    @Test
    @DisplayName("설치가 루프를 열지 않으면 available 이 거짓이다")
    void reportsUnavailable() {
        assertThat(service.get(OWNER, agent.code())).isEqualTo(new LoopSettingView(false, false, null));
    }

    @Test
    @DisplayName("켜기는 PROACTIVE_LOOP_UNAVAILABLE 로 거절하고 줄을 남기지 않는다")
    void rejectsEnabling() {
        assertThatThrownBy(() -> service.update(OWNER, agent.code(), true, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PROACTIVE_LOOP_UNAVAILABLE));
        assertThat(settings.findByUserIdAndAgentId(OWNER.id(), agent.id())).isEmpty();
    }

    @Test
    @DisplayName("끄기와 쉬기는 설치가 루프를 열지 않아도 저장한다")
    void acceptsDisablingAndSnooze() {
        Instant snooze = NOW.plus(Duration.ofDays(3));

        LoopSettingView view = service.update(OWNER, agent.code(), false, snooze);

        assertThat(view).isEqualTo(new LoopSettingView(false, false, snooze));
        assertThat(settings.findByUserIdAndAgentId(OWNER.id(), agent.id()).orElseThrow())
                .satisfies(row -> {
                    assertThat(row.enabled()).isFalse();
                    assertThat(row.snoozedUntil()).isEqualTo(snooze);
                });
    }
}
