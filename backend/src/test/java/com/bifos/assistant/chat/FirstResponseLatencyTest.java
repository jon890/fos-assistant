package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.chat.application.FirstResponseLatencyService;
import com.bifos.assistant.chat.application.ScheduledTurnExecutions;
import com.bifos.assistant.chat.application.model.LatencyRow;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.presentation.ChatDtos.LatencyRowView;
import com.bifos.assistant.chat.presentation.LatencyAdminController;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 사용자 turn 의 첫 반응 시간을 날짜와 모델 단계별로 집계하는 조회를 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class FirstResponseLatencyTest {

    private static final Long ADMIN_ID = 5_101L;
    private static final Long OTHER_USER_ID = 5_102L;

    /** 한국 시각으로 2026-10-05 12:00 이다. */
    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");

    /** 한국 시각으로 2026-10-04 12:00 이다. 한국 날짜와 세계 표준시 날짜가 같다. */
    private static final Instant YESTERDAY_NOON = Instant.parse("2026-10-04T03:00:00Z");

    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 4);

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    TaskRunRepository taskRuns;

    @Autowired
    ScheduledTurnExecutions scheduledTurns;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final List<AgentExecution> savedExecutions = new ArrayList<>();
    private final List<ChatMessage> savedMessages = new ArrayList<>();
    private final List<TaskRun> savedRuns = new ArrayList<>();
    private LatencyAdminController controller;
    private long nextConversationId = 9_100L;

    @BeforeEach
    void setUp() {
        FirstResponseLatencyService service =
                new FirstResponseLatencyService(messages, scheduledTurns, Clock.fixed(NOW, ZoneOffset.UTC));
        controller = new LatencyAdminController(currentUser, service);
        when(currentUser.require()).thenReturn(admin(UserRole.ADMIN));
        // 관리자 확인은 대역이 아니라 실제 판정을 탄다.
        doCallRealMethod().when(currentUser).requireAdmin();
    }

    @AfterEach
    void tearDown() {
        taskRuns.deleteAll(savedRuns);
        messages.deleteAll(savedMessages);
        executions.deleteAll(savedExecutions);
    }

    @Test
    @DisplayName("사용자 질문 뒤 답이 있는 실행 셋은 첫 반응 시간의 건수와 중앙값과 90번째 백분위로 집계된다")
    void aggregatesCountMedianAndNinetiethPercentileOfUserTurns() {
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON, 100L, 1_000L);
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON.plusSeconds(60), 200L, 2_000L);
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON.plusSeconds(120), 300L, 9_000L);

        LatencyRowView row = controller.latency(30).rows().getFirst();

        assertThat(controller.latency(30).rows()).hasSize(1);
        assertThat(row.date()).isEqualTo(YESTERDAY);
        assertThat(row.modelTier()).isEqualTo(ModelTier.FAST);
        assertThat(row.turns()).isEqualTo(3L);
        assertThat(row.firstResponse()).satisfies(stat -> {
            assertThat(stat.count()).isEqualTo(3L);
            assertThat(stat.p50Ms()).isEqualTo(2_000L);
            assertThat(stat.p90Ms()).isEqualTo(9_000L);
        });
        assertThat(row.toSubmit()).satisfies(stat -> {
            assertThat(stat.count()).isEqualTo(3L);
            assertThat(stat.p50Ms()).isEqualTo(200L);
            assertThat(stat.p90Ms()).isEqualTo(300L);
        });
        assertThat(row.toFirstDelta()).satisfies(stat -> {
            assertThat(stat.count()).isEqualTo(3L);
            assertThat(stat.p50Ms()).isEqualTo(1_800L);
            assertThat(stat.p90Ms()).isEqualTo(8_700L);
        });
    }

    @Test
    @DisplayName("SYSTEM 알림 줄 뒤에 답한 자동 turn 은 세지 않는다")
    void doesNotCountAutoTurnAnsweredAfterSystemNotice() {
        long conversationId = nextConversationId++;
        AgentExecution auto = execution(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON, 100L, 1_000L, null);
        message(ChatMessage.fromSystem(conversationId, "위임 결과가 도착했어요", YESTERDAY_NOON));
        message(ChatMessage.fromAssistant(conversationId, "결과를 전해요", auto.id(), YESTERDAY_NOON));
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON.plusSeconds(60), 100L, 2_000L);

        List<LatencyRow> rows = rowsOf(30);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.turns()).isEqualTo(1L);
            assertThat(row.firstResponse().p50Ms()).isEqualTo(2_000L);
        });
    }

    @Test
    @DisplayName("다시 생성한 두 번째 답의 실행도 사용자 turn 으로 센다")
    void countsExecutionOfRegeneratedAnswerAsUserTurn() {
        long conversationId = nextConversationId++;
        AgentExecution first = execution(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON, 100L, 1_000L, null);
        AgentExecution second = execution(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON.plusSeconds(30), 100L, 3_000L, null);
        message(ChatMessage.fromUser(conversationId, ADMIN_ID, "질문이에요", YESTERDAY_NOON));
        ChatMessage firstAnswer =
                message(ChatMessage.fromAssistant(conversationId, "첫 답", first.id(), YESTERDAY_NOON.plusSeconds(2)));
        message(ChatMessage.regeneratedAnswer(
                conversationId, "다시 쓴 답", second.id(), firstAnswer.id(), YESTERDAY_NOON.plusSeconds(33)));

        List<LatencyRow> rows = rowsOf(30);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.turns()).isEqualTo(2L);
            assertThat(row.firstResponse().count()).isEqualTo(2L);
            assertThat(row.firstResponse().p90Ms()).isEqualTo(3_000L);
        });
    }

    @Test
    @DisplayName("첫 조각 시각이 빈 turn 은 제출까지에만 들고 첫 반응 시간과 첫 조각까지에는 들지 않는다")
    void turnWithoutFirstDeltaCountsOnlyToSubmit() {
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON, 100L, null);

        LatencyRow row = rowsOf(30).getFirst();

        assertThat(row.turns()).isOne();
        assertThat(row.toSubmit().count()).isOne();
        assertThat(row.toSubmit().p50Ms()).isEqualTo(100L);
        assertThat(row.firstResponse().count()).isZero();
        assertThat(row.firstResponse().p50Ms()).isNull();
        assertThat(row.firstResponse().p90Ms()).isNull();
        assertThat(row.toFirstDelta().count()).isZero();
        assertThat(row.toFirstDelta().p50Ms()).isNull();
    }

    @Test
    @DisplayName("자식 실행은 세지 않는다")
    void doesNotCountChildExecution() {
        AgentExecution root = userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON, 100L, 1_000L);
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON.plusSeconds(10), 100L, 5_000L, root.id());

        List<LatencyRow> rows = rowsOf(30);

        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row.turns()).isOne());
    }

    @Test
    @DisplayName("예약 작업 발화가 연 turn 은 지시가 USER 메시지로 남아도 세지 않는다")
    void doesNotCountScheduledTaskTurnEvenThoughInstructionIsUserMessage() {
        long conversationId = nextConversationId++;
        AgentExecution scheduled = execution(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON, 100L, 1_000L, null);
        message(ChatMessage.fromSystem(conversationId, "예약 작업이 시작됐어요", YESTERDAY_NOON));
        message(ChatMessage.fromUser(conversationId, ADMIN_ID, "화요일 회의 일정을 알려 줘", YESTERDAY_NOON));
        message(ChatMessage.fromAssistant(conversationId, "회의는 화요일 10시예요", scheduled.id(), YESTERDAY_NOON));
        TaskRun run = TaskRun.queued(7_001L, 7_002L, ADMIN_ID, YESTERDAY_NOON, YESTERDAY_NOON);
        run.succeed(scheduled.id(), YESTERDAY_NOON.plusSeconds(5));
        savedRuns.add(taskRuns.save(run));
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON.plusSeconds(60), 100L, 2_000L);

        List<LatencyRow> rows = rowsOf(30);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.turns()).isOne();
            assertThat(row.firstResponse().p50Ms()).isEqualTo(2_000L);
        });
    }

    @Test
    @DisplayName("다른 사용자의 실행은 세지 않는다")
    void doesNotCountOtherUsersExecution() {
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON, 100L, 1_000L);
        userTurn(OTHER_USER_ID, ModelTier.FAST, YESTERDAY_NOON.plusSeconds(10), 100L, 8_000L);

        List<LatencyRow> rows = rowsOf(30);

        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row.turns()).isOne());
    }

    @Test
    @DisplayName("한국 시각 자정을 넘는 실행은 다음 날짜 줄로 묶인다")
    void groupsExecutionPastKoreanMidnightIntoNextDate() {
        // 세계 표준시 10월 4일 14:30 은 한국 시각 10월 4일 23:30 이고, 15:30 은 10월 5일 00:30 이다.
        userTurn(ADMIN_ID, ModelTier.FAST, Instant.parse("2026-10-04T14:30:00Z"), 100L, 1_000L);
        userTurn(ADMIN_ID, ModelTier.FAST, Instant.parse("2026-10-04T15:30:00Z"), 100L, 1_000L);

        List<LatencyRow> rows = rowsOf(30);

        assertThat(rows).extracting(LatencyRow::date).containsExactly(YESTERDAY, YESTERDAY.plusDays(1));
    }

    @Test
    @DisplayName("같은 날짜 안에서는 FAST, BALANCED, DEEP, 단계 없음 순서로 준다")
    void ordersTiersWithinDateAsFastBalancedDeepThenNone() {
        userTurn(ADMIN_ID, null, YESTERDAY_NOON, 100L, 1_000L);
        userTurn(ADMIN_ID, ModelTier.DEEP, YESTERDAY_NOON.plusSeconds(1), 100L, 1_000L);
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON.plusSeconds(2), 100L, 1_000L);
        userTurn(ADMIN_ID, ModelTier.BALANCED, YESTERDAY_NOON.plusSeconds(3), 100L, 1_000L);

        List<LatencyRow> rows = rowsOf(30);

        assertThat(rows)
                .extracting(LatencyRow::modelTier)
                .containsExactly(ModelTier.FAST, ModelTier.BALANCED, ModelTier.DEEP, null);
    }

    @Test
    @DisplayName("집계 기간보다 오래된 실행은 세지 않는다")
    void doesNotCountExecutionOlderThanPeriod() {
        userTurn(ADMIN_ID, ModelTier.FAST, NOW.minus(Duration.ofDays(40)), 100L, 1_000L);
        userTurn(ADMIN_ID, ModelTier.FAST, YESTERDAY_NOON, 100L, 2_000L);

        assertThat(rowsOf(30))
                .singleElement()
                .satisfies(row -> assertThat(row.turns()).isOne());
        assertThat(rowsOf(90)).extracting(LatencyRow::turns).containsExactly(1L, 1L);
    }

    @Test
    @DisplayName("MEMBER 역할의 요청은 FORBIDDEN 으로 거절한다")
    void rejectsMemberRoleWithForbidden() {
        when(currentUser.require()).thenReturn(admin(UserRole.MEMBER));

        assertThatThrownBy(() -> controller.latency(30))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("일수가 0 이거나 91 이면 VALIDATION_FAILED 로 거절한다")
    void rejectsDaysOutOfRangeWithValidationFailed() {
        for (int days : new int[] {0, 91}) {
            assertThatThrownBy(() -> controller.latency(days))
                    .isInstanceOfSatisfying(
                            ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    private List<LatencyRow> rowsOf(int days) {
        return controller.latency(days).rows().stream()
                .map(view -> new LatencyRow(
                        view.date(),
                        view.modelTier(),
                        view.turns(),
                        view.firstResponse(),
                        view.toSubmit(),
                        view.toFirstDelta()))
                .toList();
    }

    private static CurrentUser admin(UserRole role) {
        return new CurrentUser(ADMIN_ID, "dad@example.com", "dad", 1L, role);
    }

    private AgentExecution userTurn(Long userId, ModelTier tier, Instant received, Long submitMs, Long firstDeltaMs) {
        return userTurn(userId, tier, received, submitMs, firstDeltaMs, null);
    }

    /** 새 대화에 {@code USER} 질문과 그 실행이 낸 {@code ASSISTANT} 답을 심는다. */
    private AgentExecution userTurn(
            Long userId, ModelTier tier, Instant received, Long submitMs, Long firstDeltaMs, Long parentId) {
        long conversationId = nextConversationId++;
        AgentExecution execution = execution(userId, tier, received, submitMs, firstDeltaMs, parentId);
        message(ChatMessage.fromUser(conversationId, userId, "이번 주 일정이 어떻게 돼?", received));
        message(ChatMessage.fromAssistant(conversationId, "주간 회의는 화요일 10시예요", execution.id(), received));
        return execution;
    }

    /** 요청을 받은 시각에서 밀리초만큼 뒤에 제출하고 첫 조각을 받은 실행이다. 밀리초가 null 이면 그 시각을 비운다. */
    private AgentExecution execution(
            Long userId, ModelTier tier, Instant received, Long submitMs, Long firstDeltaMs, Long parentId) {
        AgentExecution execution = AgentExecution.builder()
                .userId(userId)
                .conversationId(9_000L)
                .parentExecutionId(parentId)
                .profileName("test-profile")
                .modelTier(tier)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(received)
                .requestReceivedAt(received)
                .build();
        if (submitMs != null) {
            execution.markSubmitted(received.plusMillis(submitMs));
        }
        if (firstDeltaMs != null) {
            execution.markFirstDelta(received.plusMillis(firstDeltaMs));
        }
        AgentExecution saved = executions.save(execution);
        savedExecutions.add(saved);
        return saved;
    }

    private ChatMessage message(ChatMessage message) {
        ChatMessage saved = messages.save(message);
        savedMessages.add(saved);
        return saved;
    }
}
