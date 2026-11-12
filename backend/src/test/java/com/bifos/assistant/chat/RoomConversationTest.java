package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.application.RoomConversations;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 기본 비서와 늘 이어지는 대화방과 날짜별 요약을 본다(docs/features/agent-team/spec.md 의 REQ8, REQ9).
 *
 * <p>가짜 Hermes 는 받은 session 을 그대로 돌려주고, 요약 요청 표지가 있는 입력에는 요약을 답한다.
 */
@BackendIntegrationTest
class RoomConversationTest {

    private static final String SUMMARY_MARK = "[대화 요약 요청]";

    private static final String NOTICE_TITLE = "어제까지 이야기를 요약해 이어요";

    /** 2026-11-11 10:00 Asia/Seoul. */
    private static final Instant DAY_ONE = Instant.parse("2026-11-11T01:00:00Z");

    /** 다음 날 00:30 Asia/Seoul. UTC 로는 아직 11일이다. */
    private static final Instant DAY_TWO = Instant.parse("2026-11-11T15:30:00Z");

    @Autowired
    ChatService chat;

    @Autowired
    RoomConversations rooms;

    @Autowired
    AgentLifecycleService lifecycle;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    TestClock clock;

    private final List<AllowedPerson> savedPeople = new ArrayList<>();
    private final AtomicInteger answers = new AtomicInteger();
    private final AtomicBoolean summaryFails = new AtomicBoolean();
    private final AtomicBoolean turnFails = new AtomicBoolean();

    private CurrentUser dad;
    private Agent primary;


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    @BeforeEach
    void setUp() {
        stub().reset();
        stub().willAnswer(this::answer);
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        clock.set(DAY_ONE);
        dad = person("dad");
        primary = agents.findByHermesProfileAndDeletedAtIsNull(profileOf(dad)).orElseThrow();
    }


    @AfterEach
    void tearDown() {
        people.deleteAll(savedPeople);
        savedPeople.clear();
    }


    @Test
    @DisplayName("대화방은 사용자마다 하나이고 기본 비서의 빈 방으로 열리며 목록에서 빠진다")
    void roomIsOnePerUserAndHiddenFromList() {
        Long older = chat.send(dad, null, "지난 대화", primary.code()).conversationId();
        CurrentUser mom = person("mom");

        Conversation room = rooms.findOrCreate(dad);

        assertThat(room.purpose()).isEqualTo(ConversationPurpose.ROOM);
        assertThat(room.agentId()).isEqualTo(primary.id());
        assertThat(room.title()).isEqualTo("대화방");
        assertThat(messages.findByConversationIdOrderByIdAsc(room.id())).as("배포 뒤 처음 열면 빈 방이다").isEmpty();
        assertThat(rooms.findOrCreate(dad).id()).isEqualTo(room.id());
        assertThat(rooms.findOrCreate(mom).id()).isNotEqualTo(room.id());
        assertThat(chat.conversationsOf(dad, null, 30).items())
            .extracting(Conversation::id)
            .as("지난 대화는 목록에 그대로 있고 대화방은 없다")
            .containsExactly(older);
    }


    @Test
    @DisplayName("대화방은 이름을 바꾸거나 지울 수 없고 기본 비서가 없으면 열지 않는다")
    void roomCannotBeRenamedOrDeleted() {
        Long roomId = rooms.findOrCreate(dad).id();

        assertRejected(() -> chat.rename(dad, roomId, "새 이름"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> chat.delete(dad, roomId), ErrorCode.VALIDATION_FAILED);

        AppUser stranger = users.save(AppUser.of("stranger@example.com", "stranger", 1L, UserRole.MEMBER));
        CurrentUser noPrimary = new CurrentUser(
            stranger.id(), stranger.email(), stranger.displayName(), stranger.groupId(), stranger.role());
        assertRejected(() -> rooms.findOrCreate(noPrimary), ErrorCode.AGENT_NOT_FOUND);
    }


    @Test
    @DisplayName("기본 비서는 주인도 ADMIN 도 지울 수 없고 다른 에이전트는 지워진다")
    void primaryAgentCannotBeDeleted() {
        Agent other = agents.save(agent("room-other-" + UUID.randomUUID(), "쇼핑 담당", dad.id()));
        CurrentUser admin = new CurrentUser(9_999L, "admin@example.com", "admin", 1L, UserRole.ADMIN);

        assertRejected(() -> lifecycle.delete(dad, primary.code()), ErrorCode.PRIMARY_AGENT_PROTECTED);
        assertRejected(() -> lifecycle.delete(admin, primary.code()), ErrorCode.PRIMARY_AGENT_PROTECTED);
        lifecycle.delete(dad, other.code());

        assertThat(agents.findById(primary.id()).orElseThrow().isDeleted()).isFalse();
        assertThat(agents.findById(other.id()).orElseThrow().isDeleted()).isTrue();
    }


    @Test
    @DisplayName("같은 날은 요약하지 않고, 날이 바뀐 첫 메시지는 옛 session 을 요약해 알림 줄을 남기고 새 session 으로 잇는다")
    void firstMessageOfNewDaySummarizesAndRenewsSession() {
        Long roomId = rooms.findOrCreate(dad).id();
        chat.send(dad, roomId, "다음 주에 등산 가", null);
        clock.advance(Duration.ofHours(10));
        chat.send(dad, roomId, "준비물은?", null);
        assertThat(summaryRequests()).as("같은 날은 요약하지 않는다").isEmpty();
        String oldSession = conversations.findById(roomId).orElseThrow().hermesSessionId();

        clock.set(DAY_TWO);
        chat.send(dad, roomId, "그중 제일 싼 걸로", null);

        List<HermesRunCommand> summaries = summaryRequests();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().sessionId()).isEqualTo(oldSession);
        HermesRunCommand turn = stub().received().getLast();
        Conversation after = conversations.findById(roomId).orElseThrow();
        assertThat(after.hermesSessionId()).startsWith("fos-").isNotEqualTo(oldSession);
        assertThat(after.hermesRootSessionId()).isEqualTo(after.hermesSessionId());
        assertThat(turn.sessionId()).isEqualTo(after.hermesSessionId());
        assertThat(turn.input())
            .contains("[어제까지의 대화 요약]")
            .contains("<external-data>\n등산 준비를 이야기했다")
            .endsWith("[사용자 메시지]\n그중 제일 싼 걸로");

        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(roomId);
        assertThat(history)
            .extracting(ChatMessage::role)
            .as("지난 메시지는 그대로 있고 요약 알림 줄이 질문 앞에 있다")
            .containsExactly(
                MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT,
                MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(history.get(4).content()).isEqualTo(NOTICE_TITLE + "\n\n등산 준비를 이야기했다");

        AgentExecution summary = executions.findAll().stream()
            .filter(execution -> execution.hermesRunId() != null && execution.conversationId() == null)
            .findFirst()
            .orElseThrow();
        assertThat(summary.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(summary.agentId()).isEqualTo(primary.id());

        chat.send(dad, roomId, "고마워", null);

        assertThat(summaryRequests()).hasSize(1);
        assertThat(stub().received().getLast().input()).doesNotContain("[어제까지의 대화 요약]").endsWith("고마워");
    }


    @Test
    @DisplayName("며칠 쉬어도 한 번만 요약하고, 요약이 실패하면 앞 session 을 잇고 다음 메시지에서 다시 요약한다")
    void failedSummaryKeepsSessionAndRetriesNextMessage() {
        Long roomId = rooms.findOrCreate(dad).id();
        chat.send(dad, roomId, "다음 주에 등산 가", null);
        String oldSession = conversations.findById(roomId).orElseThrow().hermesSessionId();
        clock.set(DAY_ONE.plus(Duration.ofDays(3)));
        summaryFails.set(true);

        chat.send(dad, roomId, "오랜만이야", null);

        assertThat(summaryRequests()).hasSize(1);
        assertThat(stub().received().getLast().sessionId()).isEqualTo(oldSession);
        assertThat(stub().received().getLast().input()).doesNotContain("[어제까지의 대화 요약]");
        assertThat(messages.findByConversationIdOrderByIdAsc(roomId))
            .extracting(ChatMessage::role)
            .doesNotContain(MessageRole.SYSTEM);
        assertThat(executions.findAll())
            .filteredOn(execution -> execution.conversationId() == null)
            .extracting(AgentExecution::status)
            .containsExactly(ExecutionStatus.FAILED);

        summaryFails.set(false);
        chat.send(dad, roomId, "다시 말해 줘", null);

        assertThat(summaryRequests()).hasSize(2);
        assertThat(conversations.findById(roomId).orElseThrow().hermesSessionId()).isNotEqualTo(oldSession);
        assertThat(stub().received().getLast().input()).contains("[어제까지의 대화 요약]");
    }


    @Test
    @DisplayName("요약을 붙인 turn 이 실패하면 다시 만들기와 다음 메시지가 요약을 다시 붙인다")
    void failedTurnCarriesSummaryAgain() {
        Long roomId = rooms.findOrCreate(dad).id();
        chat.send(dad, roomId, "다음 주에 등산 가", null);
        clock.set(DAY_TWO);
        turnFails.set(true);

        assertRejected(() -> chat.send(dad, roomId, "그중 제일 싼 걸로", null), ErrorCode.HERMES_RUN_FAILED);

        turnFails.set(false);
        chat.regenerate(dad, roomId, event -> {
        });

        assertThat(summaryRequests()).hasSize(1);
        assertThat(stub().received().getLast().input()).contains("[어제까지의 대화 요약]");

        chat.send(dad, roomId, "고마워", null);

        assertThat(stub().received().getLast().input()).doesNotContain("[어제까지의 대화 요약]");
    }


    private HermesRunResult answer(HermesRunCommand command) {
        if (command.input().startsWith(SUMMARY_MARK)) {
            return summaryFails.get()
                ? new HermesRunResult("summary-failed", command.sessionId(), "failed", "", null, null, "failed", TokenUsage.empty())
                : HermesRunResult.of(
                    "summary-" + answers.incrementAndGet(), command.sessionId(), "completed", "등산 준비를 이야기했다",
                    "model", "provider", TokenUsage.empty());
        }
        if (turnFails.get()) {
            return new HermesRunResult("turn-failed", command.sessionId(), "failed", "", null, null, "failed", TokenUsage.empty());
        }
        return HermesRunResult.of(
            "run-" + answers.incrementAndGet(), command.sessionId(), "completed", "답 " + answers.get(), "model", "provider",
            TokenUsage.empty());
    }


    private List<HermesRunCommand> summaryRequests() {
        return stub().received().stream().filter(command -> command.input().startsWith(SUMMARY_MARK)).toList();
    }


    /** 허용 목록의 사람과 사용자, 그 사람의 기본 profile 을 가리키는 첫 에이전트를 만든다. */
    private CurrentUser person(String name) {
        String profile = "room-" + name + "-" + UUID.randomUUID();
        savedPeople.add(people.save(AllowedPerson.of(profile + "@example.com", name, profile)));
        AppUser user = users.save(AppUser.of(profile + "@example.com", name, 1L, UserRole.MEMBER));
        agents.save(agent(profile, "비서", user.id()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private static String profileOf(CurrentUser user) {
        return user.email().substring(0, user.email().indexOf('@'));
    }


    private static void assertRejected(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }


    private static Agent agent(String code, String name, Long ownerId) {
        return Agent.of(
            code,
            name,
            code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            ownerId);
    }
}
