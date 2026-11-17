package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.orchestration.application.NativeDelegationResultSource;
import com.bifos.assistant.orchestration.application.NativeDelegationSweep;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.DelegationWakeEnabled;
import com.bifos.assistant.testsupport.NativeResultFixtures;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 1시간 기한과 중지한 답의 알림 줄을 본다(스펙 REQ12, DECISION31, DECISION34, DECISION40~DECISION42). */
@BackendIntegrationTest
@DelegationWakeEnabled
class NativeDelegationSweepTest {
    static final String PROFILE = "native-sweep";
    static final String EMAIL = "native-sweep@example.com";
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final String TIMEOUT_ONE = "하위 작업의 결과를 1시간 동안 받지 못했어요. 늦게 오면 이어서 알려 드릴게요";
    private static final String STOPPED = "중지한 답이 맡긴 하위 작업이 끝났어요. 이어서 하려면 메시지를 보내 주세요";

    @Autowired
    NativeDelegationSweep sweep;

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
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HermesSessionBindingRepository bindings;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppUser dad;
    private Conversation conversation;
    private AgentExecution origin;


    @BeforeEach
    void setUp() {
        Fixture fixture = Fixture.create(jdbc, users, agents, conversations, executions, NOW);
        clock.set(NOW);
        dad = fixture.dad();
        conversation = fixture.conversation();
        origin = fixture.origin(ExecutionStatus.SUCCEEDED);
    }


    @Test
    @DisplayName("등록 61분 뒤 소식 없는 자식 하나는 기한 알림 줄 하나를 남기고 다시 돌려도 늘지 않는다")
    void oneSilentChildLeavesOneTimeoutNotice() {
        waiting(origin, 61);

        sweep.sweepOnce(NOW);
        sweep.sweepOnce(NOW.plusSeconds(60));

        assertThat(contents()).containsExactly(TIMEOUT_ONE);
    }


    @Test
    @DisplayName("같은 답의 소식 없는 자식 둘은 자식 수를 적은 알림 줄 하나다")
    void twoSilentChildrenShareOneNotice() {
        waiting(origin, 61);
        waiting(origin, 62);

        sweep.sweepOnce(NOW);

        assertThat(contents()).containsExactly("하위 작업 2개의 결과를 1시간 동안 받지 못했어요. 늦게 오면 이어서 알려 드릴게요");
    }


    @Test
    @DisplayName("앞서 알림을 남긴 답에서 뒤에 기한을 넘은 자식은 칸만 적고 줄은 늘지 않는다")
    void laterOverdueChildOfNoticedTurnOnlyMarks() {
        waiting(origin, 61);
        long later = waiting(origin, 30);
        sweep.sweepOnce(NOW);

        sweep.sweepOnce(NOW.plusSeconds(40 * 60));

        assertThat(contents()).containsExactly(TIMEOUT_ONE);
        assertThat(jdbc.queryForObject("SELECT timeout_noticed_at FROM hermes_session_binding WHERE id = ?", Timestamp.class, later))
            .isNotNull();
    }


    @Test
    @DisplayName("등록 25시간 뒤의 줄과 깨울 자식이 아닌 줄은 아무것도 남기지 않는다")
    void oldOrNonEligibleRowsLeaveNothing() {
        waiting(origin, 25 * 60);
        NativeResultFixtures.binding(jdbc, PROFILE, dad.id(), origin.id(), false, NOW.minusSeconds(61 * 60), null, null, null, null);

        sweep.sweepOnce(NOW);

        assertThat(contents()).isEmpty();
    }


    @Test
    @DisplayName("기한 알림 뒤에 끝 소식이 오면 그 결과는 자동 turn 대상이 된다")
    void lateResultAfterTimeoutIsDelivered() {
        long child = waiting(origin, 61);
        sweep.sweepOnce(NOW);

        jdbc.update(
            "UPDATE hermes_session_binding SET stopped_at = ?, result_status = 'SUCCEEDED', result_text = '늦은 결과' WHERE id = ?",
            Timestamp.from(NOW.plusSeconds(60)),
            child);
        clock.set(NOW.plusSeconds(120));

        assertThat(source.undelivered(conversation.id())).hasSize(1);
    }


    @Test
    @DisplayName("중지한 답의 자식이 모두 끝나면 중지 알림 줄 하나를 남기고 결과는 전하지 않은 채 사용자 turn 에 넣을 결과로 남는다")
    void stoppedTurnWithAllChildrenDoneLeavesStopNotice() {
        AgentExecution stopped = Fixture.root(executions, dad, conversation, ExecutionStatus.CANCELLED, NOW.minusSeconds(7_200));
        long child = NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), stopped.id(), true, NOW.minusSeconds(600), NOW.minusSeconds(60), "SUCCEEDED", "결과", "일");

        sweep.sweepOnce(NOW);
        sweep.sweepOnce(NOW.plusSeconds(3_600));

        assertThat(contents()).containsExactly(STOPPED);
        assertThat(jdbc.queryForObject("SELECT stop_noticed_at FROM hermes_session_binding WHERE id = ?", Timestamp.class, child))
            .isNotNull();
        assertThat(jdbc.queryForObject("SELECT result_delivered_at FROM hermes_session_binding WHERE id = ?", Timestamp.class, child))
            .isNull();
        assertThat(source.undelivered(conversation.id())).isEmpty();
        assertThat(source.carriedIntoUserTurn(conversation.id())).singleElement()
            .satisfies(result -> assertThat(result.key()).isEqualTo(String.valueOf(child)));
    }


    @Test
    @DisplayName("사용자 메시지가 중지한 답의 결과를 모두 가져간 뒤에는 중지 알림 줄을 남기지 않는다")
    void carriedBeforeSweepLeavesNoStopNotice() {
        AgentExecution stopped = Fixture.root(executions, dad, conversation, ExecutionStatus.CANCELLED, NOW.minusSeconds(7_200));
        long child = NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), stopped.id(), true, NOW.minusSeconds(600), NOW.minusSeconds(60), "SUCCEEDED", "결과", "일");
        assertThat(source.markDelivered(List.of(String.valueOf(child)), NOW)).isEqualTo(1);

        sweep.sweepOnce(NOW.plusSeconds(60));

        assertThat(contents()).isEmpty();
        assertThat(source.carriedIntoUserTurn(conversation.id())).isEmpty();
    }


    @Test
    @DisplayName("중지한 답의 자식 하나가 30분째 돌면 아무것도 없고 61분이면 중지 알림 줄 하나다")
    void stoppedTurnWaitsForRunningChildUntilDeadline() {
        AgentExecution stopped = Fixture.root(executions, dad, conversation, ExecutionStatus.CANCELLED, NOW.minusSeconds(7_200));
        NativeResultFixtures.binding(jdbc, PROFILE, dad.id(), stopped.id(), true, NOW.minusSeconds(30 * 60), null, null, null, null);

        sweep.sweepOnce(NOW);
        assertThat(contents()).isEmpty();

        sweep.sweepOnce(NOW.plusSeconds(31 * 60));
        assertThat(contents()).containsExactly(STOPPED);
    }


    @Test
    @DisplayName("중지 알림 뒤 끝 소식이 늦게 와도 중지 알림 줄이 늘지 않고 그 결과는 사용자 turn 에 넣을 결과가 된다")
    void lateStopAfterStopNoticeDoesNotRepeatNotice() {
        AgentExecution stopped = Fixture.root(executions, dad, conversation, ExecutionStatus.CANCELLED, NOW.minusSeconds(7_200));
        long child = NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), stopped.id(), true, NOW.minusSeconds(61 * 60), null, null, null, null);
        sweep.sweepOnce(NOW);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> bindings.recordStop(
            child, NOW.plusSeconds(60), "SUCCEEDED", "늦은 결과", null, null, null));
        sweep.sweepOnce(NOW.plusSeconds(120));

        assertThat(contents()).containsExactly(STOPPED);
        assertThat(source.undelivered(conversation.id())).isEmpty();
        assertThat(source.carriedIntoUserTurn(conversation.id())).singleElement()
            .satisfies(result -> assertThat(result.input()).contains("늦은 결과"));
    }


    private long waiting(AgentExecution execution, int minutesAgo) {
        return NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), execution.id(), true, NOW.minusSeconds(minutesAgo * 60L), null, null, null, null);
    }


    private List<String> contents() {
        return messages.findByConversationIdOrderByIdAsc(conversation.id()).stream().map(ChatMessage::content).toList();
    }


    /** 이 검사의 사용자, 대화 에이전트, 대화, origin 실행이다. 같은 H2 를 다른 검사와 함께 쓰므로 이 사용자의 줄만 지운다. */
    record Fixture(AppUser dad, Agent chief, Conversation conversation, AgentExecutionRepository executions, Instant now) {

        static Fixture create(
            JdbcTemplate jdbc,
            AppUserRepository users,
            AgentRepository agents,
            ConversationRepository conversations,
            AgentExecutionRepository executions,
            Instant now) {
            NativeResultFixtures.clear(jdbc, PROFILE);
            users.findByEmail(EMAIL).ifPresent(user -> {
                jdbc.update("DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)", user.id());
                jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", user.id());
                jdbc.update("DELETE FROM conversation WHERE user_id = ?", user.id());
                jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", user.id());
                users.delete(user);
            });
            AppUser dad = users.save(AppUser.of(EMAIL, "아빠", 1L, UserRole.MEMBER));
            Agent chief = agents.save(Agent.of(
                PROFILE, "비서", PROFILE, "http://agent-runtime.test/p/" + PROFILE, CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, dad.id()));
            Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), now.minusSeconds(86_400)));
            return new Fixture(dad, chief, conversation, executions, now);
        }


        AgentExecution origin(ExecutionStatus status) {
            return root(executions, dad, conversation, status, now.minusSeconds(7_200));
        }


        static AgentExecution root(
            AgentExecutionRepository executions, AppUser dad, Conversation conversation, ExecutionStatus status, Instant startedAt) {
            return executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(conversation.agentId())
                .profileName(PROFILE)
                .hermesRunId("run-" + status)
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(startedAt)
                .build());
        }
    }
}
