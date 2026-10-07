package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.attention.application.AttentionControlService;
import com.bifos.assistant.attention.application.AttentionService;
import com.bifos.assistant.attention.application.model.AttentionCard;
import com.bifos.assistant.attention.application.model.AttentionItem;
import com.bifos.assistant.attention.application.model.AttentionView;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.attention.infra.AttentionControlRepository;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.AttentionTestCandidates;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 숨기기, 미루기, 되돌리기와 지표 사건을 실제 DB 로 본다. 규칙은 {@code docs/backend/attention.md} 의 「억제 신호」 와 「API」 다.
 *
 * <p>시각은 이 검사의 시계가 정한다. 사용자는 검사마다 새로 만들어 다른 검사의 줄과 섞이지 않게 하고, 끝나면 그 사용자의 줄을
 * 지운다. 커넥터 카탈로그는 대역이 답한다.
 */
@BackendIntegrationTest
class AttentionControlServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");

    private static final String CONNECTOR = "attention-control-notes";
    private static final String WRITE = "write_note";

    /** 승인을 받는 쓰기 도구 하나를 선언한다. 승인 줄의 이름이 이 선언에서 온다. */
    private static final ConnectorManifest MANIFEST = new ConnectorManifest(
            CONNECTOR,
            "먼저 알리기 제어 검사용 메모",
            "",
            List.of(),
            "list_scopes",
            "demo",
            List.of(),
            false,
            2,
            List.of(
                    new ConnectorTool("list_scopes", "READ", "none", null, null),
                    new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", null)));

    @Autowired
    TestClock clock;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    AttentionService attention;

    @Autowired
    AttentionControlService controls;

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

    /** 후보를 읽은 횟수를 센다. 공통 확장이 검사마다 0 으로 되돌린다. */
    @Autowired
    AttentionTestCandidates.ReadCountingCandidates readCounting;

    @Autowired
    AttentionControlRepository controlEntries;

    @Autowired
    MemoryService memories;

    private final List<Long> createdUsers = new ArrayList<>();
    private CurrentUser dad;
    private Agent chief;

    /** {@link #shownThenApprovedAction} 이 지금 화면에서 본 승인 대기 항목이다. */
    private AttentionItem shownApproval;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        when(connector.readCatalog()).thenReturn(List.of(MANIFEST));
        dad = member();
        chief = agentOf(dad, "집안일 도우미");
    }

    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM attention_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM memory WHERE owner_user_id = ?", userId);
            jdbc.update("DELETE FROM attention_control WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM connector_action WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", userId);
            jdbc.update(
                    "DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)",
                    userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", userId);
        }
        createdUsers.clear();
    }

    @Test
    @DisplayName("실패 항목을 실패 카드에서 숨기면 그 카드와 건수에서 빠지고 HIDDEN 사건이 한 줄이며 같은 대화가 이어서 하기에 보인다")
    void hidesFailureFromFailuresCardOnly() {
        Conversation conversation = failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        int before = attention.summary(dad);

        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());

        AttentionView after = attention.view(dad);
        assertThat(card(after, CardKey.FAILURES).items()).isEmpty();
        assertThat(card(after, CardKey.CONTINUE).items())
                .extracting(AttentionItem::conversationId)
                .containsExactly(conversation.publicId());
        assertThat(attention.summary(dad)).isEqualTo(before - 1);
        assertThat(events(AttentionEventType.HIDDEN))
                .containsExactly(tuple(failure.itemKey(), failure.stateKey(), "EXECUTION_FAILED", "NOW"));
    }

    @Test
    @DisplayName("같은 숨기기를 두 번 보내도 제어 줄과 HIDDEN 사건은 한 줄이다")
    void keepsOneRowForRepeatedHide() {
        failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);

        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());
        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());

        assertThat(controlRows()).containsExactly(tuple("FAILURES", "HIDE", failure.stateKey(), null));
        assertThat(events(AttentionEventType.HIDDEN)).hasSize(1);
    }

    @Test
    @DisplayName("이어서 하기 카드에서 같은 대화를 숨겨도 실패 카드의 숨기기 줄은 그대로다")
    void keepsControlsSeparatePerCard() {
        failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());
        AttentionItem recent = onlyItem(attention.view(dad), CardKey.CONTINUE);

        controls.hide(dad, CardKey.CONTINUE, recent.itemKey(), recent.stateKey());

        AttentionView after = attention.view(dad);
        assertThat(recent.itemKey()).isEqualTo(failure.itemKey());
        assertThat(card(after, CardKey.CONTINUE).items()).isEmpty();
        assertThat(card(after, CardKey.FAILURES).items()).isEmpty();
        assertThat(controlRows())
                .containsExactlyInAnyOrder(
                        tuple("FAILURES", "HIDE", failure.stateKey(), null),
                        tuple("CONTINUE", "HIDE", recent.stateKey(), null));
    }

    @Test
    @DisplayName("숨긴 대화에 새 실패가 생겨 상태가 바뀌면 다시 NOW 로 보인다")
    void showsHiddenItemAgainWhenStateChanges() {
        Conversation conversation = failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());

        message(ChatMessage.fromUser(conversation.id(), dad.id(), "한 번 더 해 줘", NOW.minusSeconds(120)));
        failedRoot(conversation, NOW.minusSeconds(120), NOW.minusSeconds(60));

        AttentionItem again = onlyItem(attention.view(dad), CardKey.FAILURES);
        assertThat(again.itemKey()).isEqualTo(failure.itemKey());
        assertThat(again.stateKey()).isNotEqualTo(failure.stateKey());
        assertThat(again.level()).isEqualTo(AttentionLevel.NOW);
    }

    @Test
    @DisplayName("내일 오전으로 미루면 지금은 빠지고 그 시각이 지난 뒤에는 다시 보인다")
    void snoozesUntilTomorrowMorning() {
        failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        Instant tomorrowMorning = Instant.parse("2026-10-05T00:00:00Z");

        controls.snooze(dad, CardKey.FAILURES, failure.itemKey(), tomorrowMorning);

        assertThat(card(attention.view(dad), CardKey.FAILURES).items()).isEmpty();
        clock.set(tomorrowMorning.plusSeconds(60));
        assertThat(onlyItem(attention.view(dad), CardKey.FAILURES).itemKey()).isEqualTo(failure.itemKey());
    }

    @Test
    @DisplayName("다른 기한으로 두 번 미루면 제어 줄의 기한은 두 번째 값이고 SNOOZED 사건은 한 줄이다")
    void keepsLatestUntilAndOneEventForRepeatedSnooze() {
        failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        Instant first = NOW.plus(Duration.ofHours(3));
        Instant second = NOW.plus(Duration.ofDays(2));

        controls.snooze(dad, CardKey.FAILURES, failure.itemKey(), first);
        controls.snooze(dad, CardKey.FAILURES, failure.itemKey(), second);

        assertThat(controlRows()).containsExactly(tuple("FAILURES", "SNOOZE", null, second));
        assertThat(events(AttentionEventType.SNOOZED))
                .containsExactly(tuple(failure.itemKey(), failure.stateKey(), "EXECUTION_FAILED", "NOW"));
    }

    @Test
    @DisplayName("미루는 기한이 9일 뒤면 VALIDATION_FAILED 다")
    void rejectsSnoozeBeyondLimit() {
        failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);

        assertCode(
                () -> controls.snooze(dad, CardKey.FAILURES, failure.itemKey(), NOW.plus(Duration.ofDays(9))),
                ErrorCode.VALIDATION_FAILED);
        assertThat(controlRows()).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 대화 열쇠로 숨기면 ATTENTION_ITEM_NOT_FOUND 다")
    void rejectsHidingOtherUsersItem() {
        CurrentUser mom = member();
        Conversation conversation = conversationOf(mom, "엄마의 대화");
        message(ChatMessage.fromUser(conversation.id(), mom.id(), "정리해 줘", NOW.minusSeconds(600)));
        executions.save(root(mom, conversation, ExecutionStatus.FAILED, NOW.minusSeconds(600), NOW.minusSeconds(540)));
        AttentionItem momsFailure = onlyItem(attention.view(mom), CardKey.FAILURES);

        assertCode(
                () -> controls.hide(dad, CardKey.FAILURES, momsFailure.itemKey(), momsFailure.stateKey()),
                ErrorCode.ATTENTION_ITEM_NOT_FOUND);
        assertThat(controlRows()).isEmpty();
    }

    @Test
    @DisplayName("승인 대기를 숨기면 DISMISSED, 미루면 POSTPONED 판단 피드백이 그 승인 줄의 열쇠로 남는다")
    void recordsDecisionFeedbackForSuggestionControls() {
        Conversation conversation = conversationOf(dad, "메모 남기기");
        UUID actionId = insertPendingAction(conversation.id(), NOW.plus(Duration.ofHours(3)));
        AttentionItem approval = onlyItem(attention.view(dad), CardKey.NEEDS_ME);

        controls.snooze(dad, CardKey.NEEDS_ME, approval.itemKey(), NOW.plus(Duration.ofHours(1)));
        controls.restore(dad, CardKey.NEEDS_ME, approval.itemKey());
        controls.hide(dad, CardKey.NEEDS_ME, approval.itemKey(), approval.stateKey());

        assertThat(feedbackRows())
                .containsExactly(
                        tuple("connector_action:" + actionId, "POSTPONED", "USER", "ATTENTION_SNOOZE"),
                        tuple("connector_action:" + actionId, "DISMISSED", "USER", "ATTENTION_HIDE"));
    }

    @Test
    @DisplayName("Memory 제안을 숨기면 제안한 실행의 대화를 채워 남기고, 그 대화를 지운 뒤 숨기면 남기지 않는다")
    void fillsOriginAndSkipsDeletedConversationForMemoryProposal() {
        Conversation kept = conversationOf(dad, "취미 이야기");
        AgentExecution keptRun = executions.save(root(dad, kept, ExecutionStatus.SUCCEEDED, NOW.minusSeconds(60), NOW));
        Memory keptProposal = memories.proposeUser(dad, "합성 취미", "합성 취미를 즐긴다", keptRun.id());
        Conversation deleted = conversationOf(dad, "지울 이야기");
        AgentExecution deletedRun =
                executions.save(root(dad, deleted, ExecutionStatus.SUCCEEDED, NOW.minusSeconds(60), NOW));
        Memory deletedProposal = memories.proposeUser(dad, "합성 일정", "합성 일정이 있다", deletedRun.id());
        jdbc.update("UPDATE conversation SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), deleted.id());

        for (AttentionItem item : card(attention.view(dad), CardKey.NEEDS_ME).items()) {
            controls.hide(dad, CardKey.NEEDS_ME, item.itemKey(), item.stateKey());
        }

        assertThat(jdbc.queryForList(
                        "SELECT subject_key, conversation_id FROM decision_feedback_event"
                                + " WHERE user_id = ? AND event_type = 'DISMISSED'",
                        dad.id()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("SUBJECT_KEY")).isEqualTo("memory:" + keptProposal.id());
                    assertThat(row.get("CONVERSATION_ID")).isEqualTo(kept.id());
                });
        assertThat(deletedProposal.id()).isNotNull();
    }

    @Test
    @DisplayName("제안이 아닌 실패 항목을 숨기면 판단 피드백을 남기지 않는다")
    void skipsDecisionFeedbackForNonSuggestions() {
        Conversation conversation = failedConversation("목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);

        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());

        assertThat(conversation.id()).isNotNull();
        assertThat(feedbackRows()).isEmpty();
    }

    @Test
    @DisplayName("보인 승인 대기가 처리돼 후보에서 빠진 뒤 보낸 ACTED 는 그 SHOWN 의 trigger 와 판정으로 남는다")
    void recordsActedAfterApprovalLeftCandidates() {
        UUID actionId = shownThenApprovedAction();
        AttentionItem approval = shownApproval;

        controls.record(dad, approval.itemKey(), approval.stateKey(), AttentionEventType.ACTED);

        assertThat(approval.itemKey()).isEqualTo("connector_action:" + actionId);
        assertThat(events(AttentionEventType.ACTED))
                .containsExactly(tuple(approval.itemKey(), approval.stateKey(), "APPROVAL_PENDING", "NOW"));
    }

    @Test
    @DisplayName("후보에서 빠진 항목에 보인 적 없는 stateKey 로 ACTED 를 보내면 ATTENTION_ITEM_NOT_FOUND 이고 사건이 없다")
    void rejectsActedWithUnshownStateAfterApprovalLeftCandidates() {
        shownThenApprovedAction();
        AttentionItem approval = shownApproval;

        assertCode(
                () -> controls.record(dad, approval.itemKey(), "0123456789abcdef", AttentionEventType.ACTED),
                ErrorCode.ATTENTION_ITEM_NOT_FOUND);
        assertThat(events(AttentionEventType.ACTED)).isEmpty();
    }

    @Test
    @DisplayName("같은 ACTED 를 두 번 보내도 사건은 한 줄이다")
    void keepsOneRowForRepeatedActed() {
        shownThenApprovedAction();
        AttentionItem approval = shownApproval;

        controls.record(dad, approval.itemKey(), approval.stateKey(), AttentionEventType.ACTED);
        controls.record(dad, approval.itemKey(), approval.stateKey(), AttentionEventType.ACTED);

        assertThat(events(AttentionEventType.ACTED)).hasSize(1);
    }

    @Test
    @DisplayName("SHOWN 도 없고 후보에도 없는 열쇠로 OPENED 를 보내면 ATTENTION_ITEM_NOT_FOUND 다")
    void rejectsOpenedForUnknownItem() {
        assertCode(
                () -> controls.record(
                        dad, "conversation:" + UUID.randomUUID(), "0123456789abcdef", AttentionEventType.OPENED),
                ErrorCode.ATTENTION_ITEM_NOT_FOUND);
        assertThat(events(AttentionEventType.OPENED)).isEmpty();
    }

    @Test
    @DisplayName("되돌리면 숨긴 항목이 다시 보인다")
    void restoresHiddenItem() {
        failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());

        controls.restore(dad, CardKey.FAILURES, failure.itemKey());

        assertThat(onlyItem(attention.view(dad), CardKey.FAILURES).itemKey()).isEqualTo(failure.itemKey());
        assertThat(controlRows()).isEmpty();
    }

    @Test
    @DisplayName("지금 화면을 두 번 읽어도 항목마다 SHOWN 은 한 줄이다")
    void recordsShownOncePerItem() {
        failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);

        attention.view(dad);

        assertThat(events(AttentionEventType.SHOWN))
                .containsExactly(tuple(failure.itemKey(), failure.stateKey(), "EXECUTION_FAILED", "NOW"));
    }

    @Test
    @DisplayName("도는 위임이 같은 상태로 LATER 에서 NOW 가 되면 SHOWN 이 판정마다 한 줄씩 남는다")
    void recordsShownForLaterAndNowOfSameState() {
        Conversation conversation = conversationOf(dad, "여행 일정 짜기");
        AgentExecution parent = executions.save(
                root(dad, conversation, ExecutionStatus.SUCCEEDED, NOW.minusSeconds(1_300), NOW.minusSeconds(1_250)));
        AgentExecution delegation = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .parentExecutionId(parent.id())
                .rootExecutionId(parent.id())
                .delegationKey("attention-control-" + UUID.randomUUID())
                .profileName(chief.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(NOW.minus(Duration.ofMinutes(20)))
                .build());
        AttentionItem later = onlyItem(attention.view(dad), CardKey.DELEGATED);

        clock.set(NOW.plus(Duration.ofMinutes(15)));
        AttentionItem now = onlyItem(attention.view(dad), CardKey.DELEGATED);

        String key = "execution:" + delegation.id();
        assertThat(later.level()).isEqualTo(AttentionLevel.LATER);
        assertThat(now.stateKey()).isEqualTo(later.stateKey());
        assertThat(events(AttentionEventType.SHOWN))
                .filteredOn(row -> key.equals(row.toList().getFirst()))
                .containsExactlyInAnyOrder(
                        tuple(key, later.stateKey(), "DELEGATION_RUNNING", "LATER"),
                        tuple(key, later.stateKey(), "DELEGATION_RUNNING", "NOW"));
    }

    @Test
    @DisplayName("모르는 카드 글로 숨기면 VALIDATION_FAILED 다")
    void rejectsUnknownCard() {
        failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);

        assertCode(() -> controls.hide(dad, null, failure.itemKey(), failure.stateKey()), ErrorCode.VALIDATION_FAILED);
        assertThat(controlRows()).isEmpty();
    }

    @Test
    @DisplayName("숨기기의 stateKey 가 65자면 후보를 읽지 않고 VALIDATION_FAILED 다")
    void rejectsLongStateKeyBeforeReading() {
        assertCode(
                () -> controls.hide(dad, CardKey.FAILURES, "conversation:" + UUID.randomUUID(), "a".repeat(65)),
                ErrorCode.VALIDATION_FAILED);
        assertThat(readCounting.reads()).as("후보를 읽은 횟수").isZero();
    }

    @Test
    @DisplayName("사건의 itemKey 가 81자면 후보를 읽지 않고 VALIDATION_FAILED 다")
    void rejectsLongItemKeyBeforeReading() {
        assertCode(
                () -> controls.record(dad, "a".repeat(81), "0123456789abcdef", AttentionEventType.OPENED),
                ErrorCode.VALIDATION_FAILED);
        assertThat(readCounting.reads()).as("후보를 읽은 횟수").isZero();
    }

    @Test
    @DisplayName("건수만 읽으면 사건이 늘지 않는다")
    void summaryRecordsNoEvent() {
        failedConversation("주간 장보기 목록 정리");

        assertThat(attention.summary(dad)).isEqualTo(1);
        assertThat(events(AttentionEventType.SHOWN)).isEmpty();
    }

    @Test
    @DisplayName("사건 종류로 SHOWN 을 보내면 VALIDATION_FAILED 다")
    void rejectsShownFromClient() {
        failedConversation("주간 장보기 목록 정리");
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);

        assertCode(
                () -> controls.record(dad, failure.itemKey(), failure.stateKey(), AttentionEventType.SHOWN),
                ErrorCode.VALIDATION_FAILED);
    }

    /** 승인 대기를 지금 화면에 한 번 보인 뒤 승인해 실행까지 끝난 것으로 바꿔 후보에서 빠지게 한다. */
    private UUID shownThenApprovedAction() {
        Conversation conversation = conversationOf(dad, "메모 남기기");
        UUID actionId = insertPendingAction(conversation.id(), NOW.plus(Duration.ofHours(3)));
        shownApproval = onlyItem(attention.view(dad), CardKey.NEEDS_ME);
        jdbc.update("UPDATE connector_action SET status = 'SUCCEEDED' WHERE public_id = ?", bytes(actionId));
        assertThat(card(attention.view(dad), CardKey.NEEDS_ME).items())
                .as("승인한 줄은 후보에서 빠진다")
                .isEmpty();
        return actionId;
    }

    /** 사용자 글 뒤에 실패한 루트 실행이 하나 있는 대화다. */
    private Conversation failedConversation(String title) {
        Conversation conversation = conversationOf(dad, title);
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(600)));
        failedRoot(conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));
        return conversation;
    }

    private void failedRoot(Conversation conversation, Instant started, Instant finished) {
        executions.save(root(dad, conversation, ExecutionStatus.FAILED, started, finished));
    }

    /** 요청자의 판단 피드백을 {@code (subjectKey, eventType, actor, reasonCode)} 로 일어난 순서대로 읽는다. */
    private List<Tuple> feedbackRows() {
        return jdbc
                .queryForList(
                        "SELECT subject_key, event_type, actor, reason_code FROM decision_feedback_event"
                                + " WHERE user_id = ? ORDER BY id",
                        dad.id())
                .stream()
                .map(row ->
                        tuple(row.get("SUBJECT_KEY"), row.get("EVENT_TYPE"), row.get("ACTOR"), row.get("REASON_CODE")))
                .toList();
    }

    /** 요청자의 사건을 {@code (itemKey, stateKey, trigger, attention)} 로 읽는다. */
    private List<Tuple> events(AttentionEventType type) {
        return jdbc
                .queryForList(
                        "SELECT item_key, state_key, trigger_type, attention FROM attention_event"
                                + " WHERE user_id = ? AND event_type = ?",
                        dad.id(),
                        type.name())
                .stream()
                .map(row ->
                        tuple(row.get("ITEM_KEY"), row.get("STATE_KEY"), row.get("TRIGGER_TYPE"), row.get("ATTENTION")))
                .toList();
    }

    /** 요청자의 제어 줄을 {@code (card, action, stateKey, until)} 로 읽는다. */
    private List<Tuple> controlRows() {
        return controlEntries.findByUserId(dad.id()).stream()
                .map(entry -> tuple(entry.cardKey().name(), entry.action().name(), entry.stateKey(), entry.untilAt()))
                .toList();
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(expected);
    }

    private CurrentUser member() {
        String email = "attention-control-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Agent agentOf(CurrentUser owner, String name) {
        String code = "attention-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
                code,
                name,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
    }

    private Conversation conversationOf(CurrentUser owner, String title) {
        return conversations.save(Conversation.startedBy(owner.id(), title, chief.id(), NOW.minusSeconds(3_600)));
    }

    private void message(ChatMessage message) {
        messages.save(message);
    }

    private AgentExecution root(
            CurrentUser owner, Conversation conversation, ExecutionStatus status, Instant started, Instant finished) {
        return AgentExecution.builder()
                .userId(owner.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .profileName(chief.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .timing(started, finished)
                .build();
    }

    /** 이 검사의 커넥터로 답을 기다리는 승인 줄 하나를 넣는다. 승인 줄을 만드는 길은 커넥터 검사가 본다. */
    private UUID insertPendingAction(Long conversationId, Instant expiresAt) {
        UUID actionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,
                    approval_mode, decision, passed, status, origin_execution_id, conversation_id, dedupe_key,
                    args_json, args_sha256, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, 'mcp__demo__write_note', 'WRITE', 'REQUIRED', 'NEEDS_APPROVAL',
                    FALSE, 'PENDING', 0, ?, ?, '{"text":"안녕"}', 'sha', ?, ?)
                """,
                bytes(actionId),
                dad.id(),
                chief.id(),
                CONNECTOR,
                WRITE,
                conversationId,
                UUID.randomUUID().toString(),
                Timestamp.from(expiresAt),
                Timestamp.from(NOW.minusSeconds(60)));
        return actionId;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static AttentionCard card(AttentionView view, CardKey key) {
        return view.cards().stream()
                .filter(card -> card.key() == key)
                .findFirst()
                .orElseThrow(() -> new AssertionError("카드 " + key + " 가 응답에 없다: " + view.cards()));
    }

    private static AttentionItem onlyItem(AttentionView view, CardKey key) {
        List<AttentionItem> items = card(view, key).items();
        assertThat(items).as("카드 %s 의 항목", key).hasSize(1);
        return items.getFirst();
    }
}
