package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.artifact.application.ArtifactPutFiles;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.conversation.application.ConversationEntry;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.nextturn.application.PendingMessageService;
import com.bifos.assistant.chat.nextturn.presentation.PendingMessageController;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.TurnHandle;
import com.bifos.assistant.chat.delivery.application.AutoTurnNoticeStatuses;
import com.bifos.assistant.chat.turn.presentation.ChatController;
import com.bifos.assistant.chat.turn.presentation.ChatDtos;
import com.bifos.assistant.chat.turn.presentation.ChatDtos.ConversationView;
import com.bifos.assistant.chat.turn.presentation.ChatEventStreams;
import com.bifos.assistant.chat.conversation.application.SourceReadSummaries;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.runtime.domain.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestPrimaryAgents;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 대화의 에이전트가 기본 비서가 아닌 대화는 읽기 전용이다(docs/features/assistant-hub/spec.md 의 REQ5).
 *
 * <p>사람이 쓰는 길(보내기, 흐르는 보내기, 다시 생성, 결과 다시 전달, 대기 메시지와 끼우기)만 막고 예약 turn 은 막지 않는다. 점검 대화는 이
 * 규칙 밖이다. 쇼핑 담당과의 지난 대화는 새 대화로 만들 수 없어 저장소로 바로 만든다.
 */
@BackendIntegrationTest
class ReadOnlyConversationTest {

    @Autowired
    TestPrimaryAgents primaries;

    @Autowired
    ChatService chat;

    @Autowired
    ConversationEntry entry;

    @Autowired
    ConversationAccess access;

    @Autowired
    PendingMessageService pending;

    @Autowired
    TurnCancellation turns;

    @Autowired
    AgentService agentService;

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
    ExecutionEventRepository executionEvents;

    @Autowired
    HermesRunsClient hermes;

    private CurrentUser dad;
    private Agent primary;
    private Agent shopper;
    private ChatController controller;
    private PendingMessageController pendingController;


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    @BeforeEach
    void setUp() {
        stub().reset();
        stub().willAnswer(command -> HermesRunResult.of(
            "run", command.sessionId(), "completed", command.profileName() + " 의 답", "model", "provider",
            TokenUsage.empty()));
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        AppUser user = users.save(AppUser.of("dad@example.com", "dad", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        primary = primaries.make(dad, agents.save(agent("dad", "비서", user.id())));
        shopper = agents.save(agent("shopper", "쇼핑 담당", user.id()));
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        when(currentUser.require()).thenReturn(dad);
        controller = new ChatController(
            chat,
            currentUser,
            new UserDisplayNameService(users),
            agentService,
            access,
            new ChatEventStreams(Duration.ofSeconds(20)),
            mock(ModelTierService.class),
            List.of(),
            mock(SourceReadSummaries.class),
            new AutoTurnNoticeStatuses(List.of()),
            mock(GeneratedImageService.class),
            mock(ArtifactPutFiles.class),
            entry);
        pendingController = new PendingMessageController(pending, currentUser, access, entry);
    }


    @Test
    @DisplayName("쇼핑 담당과의 지난 대화에 사람이 쓰는 요청은 아무것도 저장하지 않고 거절한다")
    void rejectsWritesToSpecialistConversation() {
        Conversation past = conversations.save(Conversation.startedBy(dad.id(), "지난 대화", shopper.id(), Instant.now()));
        long saved = messages.count();

        assertRejected(() -> chat.send(dad, past.id(), "운동화 찾아줘", null));
        assertRejected(() -> chat.stream(dad, past.id(), "운동화 찾아줘", null, event -> {
        }));
        assertRejected(() -> controller.regenerate(past.publicId()));
        assertRejected(() -> controller.retryDelivery(past.publicId(), 1L));
        assertRejected(() -> pendingController.enqueue(past.publicId(), new ChatDtos.PendingMessageRequest("운동화", false)));
        assertRejected(() -> pendingController.enqueue(past.publicId(), new ChatDtos.PendingMessageRequest("운동화", true)));
        assertRejected(() -> pendingController.send(past.publicId()));

        assertThat(messages.count()).isEqualTo(saved);
        assertThat(stub().received()).isEmpty();
    }


    @Test
    @DisplayName("대화 응답은 비서 대화와 점검 대화에 writable 참, 쇼핑 담당 대화에 거짓을 낸다")
    void viewCarriesWritable() {
        Conversation past = conversations.save(Conversation.startedBy(dad.id(), "지난 대화", shopper.id(), Instant.now()));
        Conversation mine = chat.startEmpty(dad, null);
        Conversation check = conversations.save(Conversation.startedForCheck(dad.id(), "점검", shopper.id(), Instant.now()));

        assertThat(controller.conversation(past.publicId()).writable()).isFalse();
        assertThat(controller.conversation(mine.publicId()).writable()).isTrue();
        assertThat(controller.conversation(check.publicId()).writable()).isTrue();
        assertThat(controller.conversations(null, 10).items())
            .extracting(ConversationView::id, ConversationView::writable)
            .containsExactlyInAnyOrder(
                tuple(past.publicId(), false),
                tuple(mine.publicId(), true),
                tuple(check.publicId(), true));
    }


    @Test
    @DisplayName("비서 대화에는 보낼 수 있고 쇼핑 담당 대화의 예약 turn 은 막지 않는다")
    void primaryConversationAndScheduledTurnStillRun() {
        Long mine = chat.send(dad, null, "안녕", null).conversationId();
        chat.send(dad, mine, "하나 더", null);
        Conversation past = conversations.save(Conversation.startedBy(dad.id(), "지난 대화", shopper.id(), Instant.now()));

        TurnHandle handle = turns.open(dad.id(), past.id());
        chat.runScheduledTurn(dad, past.id(), handle, "예약 작업", "오늘 특가 알려 줘", event -> {
        });

        assertThat(stub().received()).extracting(command -> command.profileName())
            .containsExactly("dad", "dad", "shopper");
    }


    private static void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
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
