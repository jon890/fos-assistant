package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.delivery.application.dto.AutoTurnResult;
import com.bifos.assistant.orchestration.application.NativeDelegationResultSource;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.NativeResultFixtures;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 내장 위임 결과를 자동 turn 에 낼지 정하는 조건을 본다(ADR-20261117 / native-delegation-wake, 설계 DECISION36, DECISION41). */
@BackendIntegrationTest
class NativeDelegationResultSourceTest {
    private static final String PROFILE = "native-source";
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");

    @Autowired
    NativeDelegationResultSource source;

    @Autowired
    TestClock clock;

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

    private AppUser dad;
    private Agent chief;
    private Conversation conversation;
    private AgentExecution origin;


    @BeforeEach
    void setUp() {
        NativeResultFixtures.clear(jdbc, PROFILE);
        users.findByEmail("native-source@example.com").ifPresent(user -> {
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", user.id());
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", user.id());
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", user.id());
            users.delete(user);
        });
        clock.set(NOW);
        dad = users.save(AppUser.of("native-source@example.com", "아빠", 1L, UserRole.MEMBER));
        chief = agents.save(agent("native-chief", dad.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), NOW.minusSeconds(3_600)));
        origin = root(chief.id(), NOW.minusSeconds(600), "run-origin", ExecutionStatus.SUCCEEDED);
    }


    @Test
    @DisplayName("끝난 지 5초가 안 된 결과는 빠지고 10초 된 결과는 든다")
    void resultYoungerThanFiveSecondsIsHeldBack() {
        long young = stopped(NOW.minusSeconds(3), "SUCCEEDED", "새 결과", "장보기");
        long settled = stopped(NOW.minusSeconds(10), "SUCCEEDED", "모인 결과", "장보기");

        assertThat(keys(source.undelivered(conversation.id()))).containsExactly(String.valueOf(settled)).doesNotContain(String.valueOf(young));
    }


    @Test
    @DisplayName("대화 에이전트로 제출된 루트 실행이 끝 소식 30초 넘게 뒤 시작했으면 읽은 것으로 보고 뺀다")
    void resultReadByLaterSubmittedTurnIsExcluded() {
        Instant stoppedAt = NOW.minusSeconds(120);
        long result = stopped(stoppedAt, "SUCCEEDED", "결과", "장보기");
        root(chief.id(), stoppedAt.plusSeconds(10), "run-early", ExecutionStatus.SUCCEEDED);
        assertThat(keys(source.undelivered(conversation.id()))).as("10초 뒤 시작한 turn").containsExactly(String.valueOf(result));

        Agent other = agents.save(agent("native-other", dad.id()));
        root(other.id(), stoppedAt.plusSeconds(31), "run-mention", ExecutionStatus.SUCCEEDED);
        root(chief.id(), stoppedAt.plusSeconds(31), null, ExecutionStatus.FAILED);
        assertThat(keys(source.undelivered(conversation.id()))).as("@ 맡김 turn, 제출 전 실패").containsExactly(String.valueOf(result));

        root(chief.id(), stoppedAt.plusSeconds(31), "run-late", ExecutionStatus.SUCCEEDED);
        assertThat(source.undelivered(conversation.id())).as("31초 뒤 제출된 turn").isEmpty();
    }


    @Test
    @DisplayName("origin 이 중지됐거나 깨울 자식이 아니면 빠진다")
    void cancelledOriginOrNonEligibleIsExcluded() {
        NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), origin.id(), false, NOW.minusSeconds(600), NOW.minusSeconds(60), "SUCCEEDED", "글", null);
        AgentExecution stopped = root(chief.id(), NOW.minusSeconds(300), "run-stopped", ExecutionStatus.CANCELLED);
        NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), stopped.id(), true, NOW.minusSeconds(300), NOW.minusSeconds(60), "SUCCEEDED", "글", null);

        assertThat(source.undelivered(conversation.id())).isEmpty();
        assertThat(source.conversationsWithUndelivered()).doesNotContain(conversation.id());
    }


    @Test
    @DisplayName("알림 글은 상태와 맡긴 일로 만들고, 입력은 결과 글을 외부 데이터로 감싸며, 상태를 알림 줄 상태로 낸다")
    void noticeInputAndStatusAreBuiltFromStoredResult() {
        stopped(NOW.minusSeconds(60), "FAILED", "권한이 없어 실패했다", "장보기 목록 정리");
        stopped(NOW.minusSeconds(30), "SUCCEEDED", null, null);

        List<AutoTurnResult> results = source.undelivered(conversation.id());

        assertThat(results).extracting(AutoTurnResult::notice)
            .containsExactly("하위 작업을 하지 못했어요 · 장보기 목록 정리", "하위 작업이 끝났어요");
        assertThat(results).extracting(AutoTurnResult::noticeStatus).containsExactly("FAILED", "SUCCEEDED");
        assertThat(results.getFirst().input())
            .startsWith("Hermes 하위 작업의 결과가 도착했다.\n[출처: Hermes 하위 작업, 맡긴 일: 장보기 목록 정리, 상태: FAILED, 끝난 시각: ")
            .contains("<external-data>\n권한이 없어 실패했다\n</external-data>");
        assertThat(results.get(1).input()).doesNotContain("<external-data>");
        assertThat(results).extracting(AutoTurnResult::unattended).containsOnly(false);
        assertThat(source.conversationsWithUndelivered()).contains(conversation.id());
    }


    @Test
    @DisplayName("전했다는 표시는 한 번만 세고, 다시 전달은 그 사용자의 결과만 읽으며, 알림 줄 번호는 처음 값만 남는다")
    void markDeliveredResultsForAndNoticesSaved() {
        long result = stopped(NOW.minusSeconds(60), "SUCCEEDED", "결과", "장보기");
        String key = String.valueOf(result);

        assertThat(source.markDelivered(List.of(key, "not-a-number"), NOW)).isEqualTo(1);
        assertThat(source.markDelivered(List.of(key), NOW)).isZero();
        assertThat(source.undelivered(conversation.id())).isEmpty();
        assertThat(keys(source.resultsFor(conversation.id(), dad.id(), List.of(key)))).containsExactly(key);
        assertThat(source.resultsFor(conversation.id(), dad.id() + 1000, List.of(key))).isEmpty();

        source.noticesSaved(Map.of(key, 11L));
        source.noticesSaved(Map.of(key, 22L));
        assertThat(jdbc.queryForObject("SELECT notice_message_id FROM hermes_session_binding WHERE id = ?", Long.class, result))
            .isEqualTo(11L);
    }


    private long stopped(Instant stoppedAt, String status, String text, String goal) {
        return NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), origin.id(), true, stoppedAt.minusSeconds(60), stoppedAt, status, text, goal);
    }


    private AgentExecution root(Long agentId, Instant startedAt, String hermesRunId, ExecutionStatus status) {
        return executions.save(AgentExecution.builder()
            .userId(dad.id())
            .conversationId(conversation.id())
            .agentId(agentId)
            .profileName(PROFILE)
            .hermesRunId(hermesRunId)
            .costMode(CostMode.SUBSCRIPTION)
            .status(status)
            .startedAt(startedAt)
            .build());
    }


    private static List<String> keys(List<AutoTurnResult> results) {
        return results.stream().map(AutoTurnResult::key).toList();
    }


    private static Agent agent(String code, Long ownerId) {
        return Agent.of(
            code,
            code,
            code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            ownerId);
    }
}
