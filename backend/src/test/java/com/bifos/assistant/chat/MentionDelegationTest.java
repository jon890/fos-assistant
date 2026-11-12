package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.dto.AgentLabel;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.dto.DelegationLine;
import com.bifos.assistant.chat.turn.application.dto.MentionMarks;
import com.bifos.assistant.chat.turn.application.dto.TurnIntent;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.hermes.infra.HermesModelClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.chat.modelchoice.application.ModelSettingStore;
import com.bifos.assistant.chat.modelchoice.application.ModelVisibilityService;
import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.testsupport.ConversationTierChoice;
import com.bifos.assistant.user.infra.GroupSettingRepository;
import org.springframework.transaction.support.TransactionTemplate;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 대화 중 {@code @} 로 고른 에이전트에게 바로 맡기는 길을 본다(docs/features/agent-team/spec.md 의 REQ7).
 *
 * <p>가짜 Hermes 는 받은 session 을 그대로 돌려준다. 맡김이 대화의 session 을 바꾸지 않는지 보려는 것이다.
 */
@BackendIntegrationTest
class MentionDelegationTest {

    @Autowired
    ChatService chat;

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

    @Autowired
    ModelSettingStore settings;

    @Autowired
    ModelVisibilityService visibility;

    @Autowired
    GroupSettingRepository groupSettings;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    HermesModelClient modelClient;

    /** 모델 숨김을 걸 그룹이다. 다른 검사가 쓰는 그룹의 설정을 건드리지 않게 따로 둔다. */
    private static final Long HIDDEN_GROUP_ID = 9_000_000_723L;

    private CurrentUser dad;
    private Agent chief;
    private Agent shopper;
    private final AtomicInteger answers = new AtomicInteger();


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    @BeforeEach
    void setUp() {
        stub().reset();
        stub().willAnswer(command -> HermesRunResult.of(
            "run-" + answers.incrementAndGet(),
            command.sessionId(),
            "completed",
            command.profileName() + " 의 답 " + answers.get(),
            "model",
            "provider",
            TokenUsage.empty()));
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        AppUser user = users.save(AppUser.of("dad@example.com", "dad", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        chief = agents.save(agent("dad", "비서", user.id()));
        shopper = agents.save(agent("shopper", "쇼핑 담당", user.id()));
    }


    @Test
    @DisplayName("맡기면 고른 에이전트가 새 session 으로 대화의 앞 줄과 사용자 글을 받아 답하고 대화의 에이전트와 session 은 그대로다")
    void mentionRunsChosenAgentInFreshSession() {
        Long conversationId = chat.send(dad, null, "다음 주에 등산 가", "dad").conversationId();
        Conversation before = conversations.findById(conversationId).orElseThrow();

        chat.send(dad, conversationId, "운동화 찾아줘", null, List.of(), "shopper");

        HermesRunCommand command = stub().received().getLast();
        assertThat(command.profileName()).isEqualTo("shopper");
        assertThat(command.sessionId()).startsWith("fos-").isNotEqualTo(before.hermesSessionId());
        assertThat(command.input())
            .contains("[출처: 대화의 앞 줄, 대화 에이전트: 비서, 줄 수: 2]")
            .contains("사용자: 다음 주에 등산 가")
            .contains("비서: dad 의 답 1")
            .contains("<external-data>")
            .endsWith("[맡긴 일]\n운동화 찾아줘");
        assertThat(command.instructions()).endsWith(TurnIntent.MENTION_INSTRUCTION);

        Conversation after = conversations.findById(conversationId).orElseThrow();
        assertThat(after.agentId()).isEqualTo(chief.id());
        assertThat(after.hermesSessionId()).isEqualTo(before.hermesSessionId());

        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);
        assertThat(history)
            .extracting(ChatMessage::role)
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(history.getLast().content()).isEqualTo("shopper 의 답 2");
        AgentExecution mention = executions.findById(history.getLast().executionId()).orElseThrow();
        assertThat(mention.agentId()).isEqualTo(shopper.id());
        assertThat(mention.parentExecutionId()).isNull();
        assertThat(mention.delegationKey()).as("자동 결과 전달을 타지 않는다").isNull();
        assertThat(mention.questionMessageId()).isEqualTo(history.get(2).id());

        MentionMarks marks = chat.mentionMarks(dad, conversationId);
        AgentLabel label = AgentLabel.of(shopper);
        assertThat(marks.questions())
            .containsOnlyKeys(history.get(2).id())
            .containsEntry(history.get(2).id(), new DelegationLine(mention.id(), null, ExecutionStatus.SUCCEEDED, label));
        assertThat(marks.answers()).containsOnlyKeys(mention.id()).containsEntry(mention.id(), label);
    }


    @Test
    @DisplayName("대화 에이전트의 다음 turn 은 맡김과 그 답을 머리줄과 함께 받고 그다음 turn 에는 다시 받지 않는다")
    void nextTurnCarriesMentionOnce() {
        Long conversationId = chat.send(dad, null, "다음 주에 등산 가", "dad").conversationId();
        String session = conversations.findById(conversationId).orElseThrow().hermesSessionId();
        chat.send(dad, conversationId, "운동화 찾아줘", null, List.of(), "shopper");
        Long mentionId = messages.findByConversationIdOrderByIdAsc(conversationId).getLast().executionId();

        chat.send(dad, conversationId, "그중 제일 싼 걸로", null);

        HermesRunCommand next = stub().received().getLast();
        assertThat(next.profileName()).isEqualTo("dad");
        assertThat(next.sessionId()).isEqualTo(session);
        assertThat(next.input())
            .contains("[사용자가 @ 로 다른 에이전트에게 바로 맡긴 일]")
            .contains("[출처: @ 로 맡긴 일, 에이전트: 쇼핑 담당, 실행 번호: " + mentionId + ", 상태: SUCCEEDED, 끝난 시각: ")
            .contains("사용자: 운동화 찾아줘")
            .contains("shopper 의 답 2")
            .endsWith("[사용자 메시지]\n그중 제일 싼 걸로")
            .doesNotContain("다음 주에 등산 가");

        chat.send(dad, conversationId, "고마워", null);

        assertThat(stub().received().getLast().input())
            .doesNotContain("[사용자가 @ 로 다른 에이전트에게 바로 맡긴 일]")
            .endsWith("고마워");
    }


    @Test
    @DisplayName("실패한 맡김은 답 없이 실패 상태로 다음 turn 에 실리고 대화의 session 은 그대로다")
    void failedMentionCarriesStatus() {
        Long conversationId = chat.send(dad, null, "다음 주에 등산 가", "dad").conversationId();
        String session = conversations.findById(conversationId).orElseThrow().hermesSessionId();
        stub().willAnswer(command -> new HermesRunResult(
            "failed", "fos-other", "failed", "", null, null, "failed", TokenUsage.empty()));
        assertRejected(
            () -> chat.send(dad, conversationId, "운동화 찾아줘", null, List.of(), "shopper"), ErrorCode.HERMES_RUN_FAILED);
        AgentExecution failed = executions.findAll().stream()
            .filter(execution -> shopper.id().equals(execution.agentId()))
            .findFirst()
            .orElseThrow();
        assertThat(chat.mentionMarks(dad, conversationId).questions().values())
            .extracting(DelegationLine::executionId, DelegationLine::status)
            .containsExactly(tuple(failed.id(), ExecutionStatus.FAILED));
        assertThat(conversations.findById(conversationId).orElseThrow().hermesSessionId()).isEqualTo(session);

        stub().willAnswer(command -> HermesRunResult.of(
            "run-next", command.sessionId(), "completed", "다음 답", "model", "provider", TokenUsage.empty()));
        chat.send(dad, conversationId, "다시 알려 줘", null);

        assertThat(stub().received().getLast().input())
            .contains("[출처: @ 로 맡긴 일, 에이전트: 쇼핑 담당, 실행 번호: " + failed.id() + ", 상태: FAILED")
            .contains("사용자: 운동화 찾아줘")
            .doesNotContain("<external-data>");
    }


    @Test
    @DisplayName("모델 설정 해석이 실패한 맡김도 질문에 이어져 맡김으로 보이고 다음 turn 에 실리며 다시 만들지 못한다")
    void modelResolveFailureStillMarksMention() {
        AppUser kidRow = users.save(AppUser.of("kid@example.com", "kid", HIDDEN_GROUP_ID, UserRole.MEMBER));
        CurrentUser kid = new CurrentUser(
            kidRow.id(), kidRow.email(), kidRow.displayName(), kidRow.groupId(), kidRow.role());
        agents.save(agent("kid", "아이 비서", kid.id()));
        Agent kidShopper = agents.save(agent("kid-shop", "아이 쇼핑", kid.id()));
        Long conversationId = chat.send(kid, null, "안녕", "kid").conversationId();
        HermesModelCatalog catalog = new HermesModelCatalog(
            "provider-b",
            "model-old",
            List.of(new HermesModelCatalog.Provider("provider-b", "provider-b", List.of("model-old"), Map.of())));
        when(modelClient.readCatalog(anyString(), anyString())).thenReturn(catalog);
        when(modelClient.readCatalog(anyString(), anyString(), anyBoolean())).thenReturn(catalog);
        try {
            ConversationTierChoice.defineAndChoose(
                settings,
                conversations,
                transactions,
                HIDDEN_GROUP_ID,
                kid.id(),
                conversationId,
                ModelTier.values()[0],
                ModelChoice.of("provider-b", "model-old", null));
            visibility.save(
                new CurrentUser(kid.id(), kid.email(), kid.displayName(), HIDDEN_GROUP_ID, UserRole.ADMIN),
                List.of(new HiddenModels.Entry("provider-b", "model-old")));

            assertRejected(
                () -> chat.send(kid, conversationId, "운동화 찾아줘", null, List.of(), "kid-shop"), ErrorCode.MODEL_HIDDEN);

            AgentExecution failed = executions.findAll().stream()
                .filter(execution -> kidShopper.id().equals(execution.agentId()))
                .findFirst()
                .orElseThrow();
            assertThat(chat.mentionMarks(kid, conversationId).questions().values())
                .extracting(DelegationLine::executionId, DelegationLine::status)
                .containsExactly(tuple(failed.id(), ExecutionStatus.FAILED));
            assertRejected(() -> chat.regenerate(kid, conversationId, event -> {
            }), ErrorCode.VALIDATION_FAILED);
        } finally {
            ConversationTierChoice.clear(groupSettings, transactions, HIDDEN_GROUP_ID);
        }

        chat.send(kid, conversationId, "다시 알려 줘", null);

        assertThat(stub().received().getLast().input())
            .contains("에이전트: 아이 쇼핑")
            .contains("상태: FAILED, 오류: MODEL_HIDDEN")
            .contains("사용자: 운동화 찾아줘");
    }


    @Test
    @DisplayName("새 대화, 빈 대화, 사진, 대화의 에이전트 자신, 없거나 꺼진 에이전트는 아무것도 남기지 않고 거절한다")
    void rejectsBeforeSaving() {
        Long conversationId = chat.send(dad, null, "안녕", "dad").conversationId();
        Long empty = chat.startEmpty(dad, "dad").id();
        Agent off = agents.save(agent("off", "꺼진 담당", dad.id()));
        off.changeAccess(false, AgentVisibility.PRIVATE, dad.id());
        agents.save(off);
        long saved = messages.count();
        int runs = stub().received().size();

        assertRejected(() -> chat.send(dad, null, "찾아줘", "dad", List.of(), "shopper"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> chat.send(dad, empty, "찾아줘", null, List.of(), "shopper"), ErrorCode.VALIDATION_FAILED);
        assertRejected(
            () -> chat.send(dad, conversationId, "찾아줘", null, List.of(1L), "shopper"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> chat.send(dad, conversationId, "찾아줘", null, List.of(), "dad"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> chat.send(dad, conversationId, "찾아줘", null, List.of(), "nobody"), ErrorCode.AGENT_NOT_FOUND);
        assertRejected(() -> chat.send(dad, conversationId, "찾아줘", null, List.of(), "off"), ErrorCode.AGENT_DISABLED);

        assertThat(messages.count()).isEqualTo(saved);
        assertThat(stub().received()).hasSize(runs);
    }


    @Test
    @DisplayName("남의 비공개 에이전트에게는 맡기지 못한다")
    void rejectsOthersPrivateAgent() {
        AppUser mom = users.save(AppUser.of("mom@example.com", "mom", 1L, UserRole.MEMBER));
        agents.save(agent("mom-shop", "엄마 쇼핑", mom.id()));
        Long conversationId = chat.send(dad, null, "안녕", "dad").conversationId();

        assertRejected(
            () -> chat.send(dad, conversationId, "찾아줘", null, List.of(), "mom-shop"), ErrorCode.AGENT_NOT_FOUND);
    }


    @Test
    @DisplayName("맡김의 답과 질문은 다시 만들지 못한다")
    void regenerateRejectsMention() {
        Long conversationId = chat.send(dad, null, "안녕", "dad").conversationId();
        chat.send(dad, conversationId, "운동화 찾아줘", null, List.of(), "shopper");

        assertRejected(() -> chat.regenerate(dad, conversationId, event -> {
        }), ErrorCode.VALIDATION_FAILED);
    }


    private static void assertRejected(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(code);
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
