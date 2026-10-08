package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.proactive.application.ProactiveLoopProperties;
import com.bifos.assistant.proactive.application.ProactiveLoopSettingService;
import com.bifos.assistant.proactive.application.model.LoopSettingView;
import com.bifos.assistant.proactive.domain.ProactiveLoopSetting;
import com.bifos.assistant.proactive.infra.ProactiveLoopSettingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 설치가 매일 루프를 연 상태에서 사용자 설정의 저장, 쉬기 범위, 권한을 본다. */
@BackendIntegrationTest
@OverrideProperties("assistant.proactive-loop.enabled=true")
class ProactiveLoopSettingTest {

    private static final Instant NOW = Instant.parse("2026-03-02T00:00:00Z");
    private static final CurrentUser OWNER =
            new CurrentUser(962_001L, "owner@example.com", "사용자A", 1L, UserRole.MEMBER);
    private static final CurrentUser OTHER =
            new CurrentUser(962_002L, "other@example.com", "사용자B", 1L, UserRole.MEMBER);

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

    @Autowired
    AgentService agentService;

    @Autowired
    LiveProperties<ProactiveLoopProperties> properties;

    @Autowired
    TransactionTemplate transactions;

    private Agent agent;

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
                OWNER.id(),
                NOW));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM proactive_loop_setting WHERE agent_id = ?", agent.id());
        jdbc.update("DELETE FROM agent WHERE id = ?", agent.id());
    }

    @Test
    @DisplayName("설정 줄이 없으면 꺼짐이고 설치가 루프를 열었으므로 켤 수 있다")
    void returnsDisabledWithoutRow() {
        LoopSettingView view = service.get(OWNER, agent.code());

        assertThat(view).isEqualTo(new LoopSettingView(true, false, null));
        assertThat(settings.findByUserIdAndAgentId(OWNER.id(), agent.id())).isEmpty();
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
    @DisplayName("이미 있는 줄을 끄면 새 줄을 만들지 않고 그 줄을 바꾼다")
    void changesExistingRow() {
        service.update(OWNER, agent.code(), true, null);
        Long id = settings.findByUserIdAndAgentId(OWNER.id(), agent.id())
                .orElseThrow()
                .id();

        LoopSettingView view = service.update(OWNER, agent.code(), false, null);

        assertThat(view).isEqualTo(new LoopSettingView(true, false, null));
        assertThat(settings.findByUserIdAndAgentId(OWNER.id(), agent.id()).orElseThrow())
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(id);
                    assertThat(row.enabled()).isFalse();
                });
    }

    @Test
    @DisplayName("처음 저장이 다른 요청과 겹쳐 유일 제약에 걸리면 새 트랜잭션에서 먼저 저장된 줄을 다시 읽어 바꾼다")
    void retriesOnConcurrentFirstSave() {
        Long existingId = settings.save(ProactiveLoopSetting.of(OWNER.id(), agent.id(), false, null, NOW))
                .id();
        // 첫 읽기만 줄이 없다고 답해, 다른 요청이 그사이 먼저 저장한 상황을 만든다. 저장은 실제 표로 가므로 실제 유일 제약에 걸린다.
        ProactiveLoopSettingRepository racing = mock(ProactiveLoopSettingRepository.class, delegatesTo(settings));
        doAnswer(invocation -> Optional.empty())
                .doAnswer(invocation ->
                        settings.findByUserIdAndAgentId(invocation.getArgument(0), invocation.getArgument(1)))
                .when(racing)
                .findByUserIdAndAgentId(OWNER.id(), agent.id());
        ProactiveLoopSettingService racingService =
                new ProactiveLoopSettingService(agentService, racing, properties, clock, transactions);
        Instant snooze = NOW.plus(Duration.ofDays(1));

        LoopSettingView view = racingService.update(OWNER, agent.code(), true, snooze);

        assertThat(view).isEqualTo(new LoopSettingView(true, true, snooze));
        assertThat(settings.findAll().stream()
                        .filter(row -> row.agentId().equals(agent.id()))
                        .toList())
                .as("설정 줄은 하나이고 먼저 저장된 줄이 바뀐다")
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(existingId);
                    assertThat(row.enabled()).isTrue();
                    assertThat(row.snoozedUntil()).isEqualTo(snooze);
                });
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
        assertThat(settings.findByUserIdAndAgentId(OWNER.id(), agent.id()).orElseThrow())
                .satisfies(row -> {
                    assertThat(row.enabled()).isTrue();
                    assertThat(row.snoozedUntil()).isEqualTo(limit);
                });
    }

    @Test
    @DisplayName("지난 쉬기 시각은 비운 것으로 저장한다")
    void storesPastSnoozeAsEmpty() {
        LoopSettingView view = service.update(OWNER, agent.code(), true, NOW.minus(Duration.ofMinutes(1)));

        assertThat(view).isEqualTo(new LoopSettingView(true, true, null));
        assertThat(settings.findByUserIdAndAgentId(OWNER.id(), agent.id())
                        .orElseThrow()
                        .snoozedUntil())
                .isNull();
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
        assertThat(settings.findByUserIdAndAgentId(OTHER.id(), agent.id())).isEmpty();
    }
}
