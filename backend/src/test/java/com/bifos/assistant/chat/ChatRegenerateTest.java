package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ArtifactService;
import com.bifos.assistant.chat.application.AskFormat;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.ResearchAndBuildFlow;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ChatRegenerateTest {

    @Autowired
    ChatService chat;
    /** 결과물 폴더 단락의 문구는 {@code ArtifactTest} 가 글자 그대로 견준다. 여기서는 그 단락을 받아 쓴다. */
    @Autowired
    ArtifactService artifactService;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ChatAttachmentRepository attachmentRows;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AttachmentService attachments;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    HermesRunsClient hermes;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void reset() {
        stub().reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        attachmentRows.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("마지막 답을 다시 만들면 이전 답을 가리키고 질문은 늘지 않는다")
    void regeneratingLastReplyPointsToPreviousReplyAndAddsNoQuestion() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(result("first", "첫 답"), result("second", "새 답"));

        Long conversationId = chat.send(dad, null, "원래 질문", "dad").conversationId();
        List<ChatEvent> events = new ArrayList<>();
        chat.regenerate(dad, conversationId, events::add);

        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);
        assertThat(history)
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT);
        assertThat(history.getLast().replacesMessageId())
                .isEqualTo(history.get(1).id());
        // 다시 생성도 같은 결과물 폴더 단락을 붙인 같은 질문을 보낸다.
        String expected = artifactService.agentPreamble(
                        conversations.findById(conversationId).orElseThrow()) + "원래 질문";
        assertThat(stub().received()).extracting(HermesRunCommand::input).containsExactly(expected, expected);
        assertThat(stub().received().getLast().instructions())
                .endsWith("사용자가 바로 앞 질문에 대한 답을 다시 받기를 원한다. 앞의 답을 되풀이하지 말고 새로 답한다.");
        assertThat(events.getLast().type()).isEqualTo("done");
    }

    @Test
    @DisplayName("다시 생성이 실패하면 이전 답 뒤에 새 메시지를 남기지 않는다")
    void leavesNoNewMessageAfterPreviousReplyWhenRegenerationFails() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        stub().willReturn(new HermesRunResult(
                "failed", "session", "failed", "", null, null, "failed", TokenUsage.empty()));

        assertThatThrownBy(() -> chat.regenerate(dad, conversationId, event -> {}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_RUN_FAILED);

        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)).hasSize(2);
    }

    @Test
    @DisplayName("답 없는 질문을 다시 시도하면 답은 새로 생기고 판을 가리키지 않는다")
    void retryingUnansweredQuestionCreatesReplyWithoutPointingToVersion() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(new HermesRunResult(
                "failed", "session", "failed", "", null, null, "failed", TokenUsage.empty()));
        Long conversationId;
        try {
            conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        } catch (ApiException ex) {
            conversationId = messages.findAll().getFirst().conversationId();
        }
        stub().willReturn(result("retry", "새 답"));

        chat.regenerate(dad, conversationId, event -> {});

        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);
        assertThat(history).hasSize(2);
        assertThat(history.getLast().replacesMessageId()).isNull();
        assertThat(stub().received().getLast().instructions())
                .contains("GFM", "| --- | --- |")
                .endsWith(AskFormat.GUIDE);
    }

    @Test
    @DisplayName("빈 대화는 다시 만들 답이 없다")
    void emptyConversationHasNoReplyToRegenerate() {
        CurrentUser dad = member("dad@example.com", "dad");
        Agent agent = agents.findByCode("dad").orElseThrow();
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", agent.id(), Instant.now()));

        assertThatThrownBy(() -> chat.regenerate(dad, conversation.id(), event -> {}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.MESSAGE_NOT_LATEST);
    }

    @Test
    @DisplayName("다른 사용자의 대화는 없는 대화처럼 거절한다")
    void rejectsOtherUsersConversationAsMissing() {
        CurrentUser dad = member("dad@example.com", "dad");
        CurrentUser mom = member("mom@example.com", "mom");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();

        assertThatThrownBy(() -> chat.regenerate(mom, conversationId, event -> {}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다시 생성은 대화가 기억한 Hermes session을 이어서 쓴다")
    void regenerationContinuesHermesSessionRememberedByConversation() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(
                        HermesRunResult.of(
                                "first", "shared-session", "completed", "첫 답", "model", "provider", TokenUsage.empty()),
                        HermesRunResult.of(
                                "second",
                                "shared-session",
                                "completed",
                                "새 답",
                                "model",
                                "provider",
                                TokenUsage.empty()));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();

        chat.regenerate(dad, conversationId, event -> {});

        assertThat(stub().received().getLast().sessionId()).isEqualTo("shared-session");
    }

    @Test
    @DisplayName("모델을 고른 대화에서 다시 생성하면 요청에 그 선택이 실린다")
    void regeneratingInConversationWithChosenModelCarriesChoiceInRequest() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(result("first", "첫 답"), result("second", "새 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        chat.chooseModel(dad, conversationId, new ModelChoice("nvidia", "example-model-small", "high"));

        chat.regenerate(dad, conversationId, event -> {});

        assertThat(stub().received()).hasSize(2);
        assertThat(stub().received().getFirst().model()).as("고르기 전 보내기의 모델").isNull();
        assertThat(stub().received().getLast()).satisfies(command -> {
            assertThat(command.provider()).isEqualTo("nvidia");
            assertThat(command.model()).isEqualTo("example-model-small");
            assertThat(command.reasoningEffort()).isEqualTo("high");
        });
    }

    @Test
    @DisplayName("지운 대화에서는 다시 생성을 거절한다")
    void rejectsRegenerationInDeletedConversation() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        chat.delete(dad, conversationId);

        assertThatThrownBy(() -> chat.regenerate(dad, conversationId, event -> {}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("도는 재생성 중에는 둘째 재생성을 거절한다")
    void rejectsSecondRegenerationWhileOneIsRunning() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(result("first", "첫 답"), result("regenerated", "새 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub().beforeAwait(() -> {
            awaiting.countDown();
            try {
                assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> chat.regenerate(dad, conversationId, event -> {}));
            assertThat(awaiting.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> chat.regenerate(dad, conversationId, event -> {}))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.CONVERSATION_BUSY);

            release.countDown();
            first.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId))
                .filteredOn(message -> message.role() == MessageRole.ASSISTANT)
                .hasSize(2);
    }

    @Test
    @DisplayName("도는 turn이 있는 대화는 재생성을 거절하고 새 실행을 만들지 않는다")
    void rejectsRegenerationWhileTurnRunsAndCreatesNoNewRun() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(result("first", "첫 답"), result("running", "새 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub().beforeAwait(() -> {
            awaiting.countDown();
            try {
                assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(() -> chat.send(dad, conversationId, "다음 질문", null));
            assertThat(awaiting.await(1, TimeUnit.SECONDS)).isTrue();
            long executionCount = executions.count();

            assertThatThrownBy(() -> chat.regenerate(dad, conversationId, event -> {}))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.CONVERSATION_BUSY);
            assertThat(executions.count()).isEqualTo(executionCount);

            release.countDown();
            running.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("다시 생성을 거듭하면 새 답이 직전 답을 가리킨다")
    void repeatedRegenerationPointsNewReplyToPreviousOne() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(result("first", "첫 답"), result("second", "둘째 답"), result("third", "셋째 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        chat.regenerate(dad, conversationId, event -> {});
        chat.regenerate(dad, conversationId, event -> {});

        List<ChatMessage> answers = messages.findByConversationIdOrderByIdAsc(conversationId).stream()
                .filter(message -> message.role() == MessageRole.ASSISTANT)
                .toList();
        assertThat(answers).hasSize(3);
        assertThat(answers.get(2).replacesMessageId()).isEqualTo(answers.get(1).id());
    }

    @Test
    @DisplayName("흐름 재생성은 모든 실행의 지시에 문구를 붙이고 답 판을 잇는다")
    void flowRegenerationAppendsPhraseToAllRunsAndLinksReplyVersion() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        Long originalAnswer = messages.findByConversationIdOrderByIdAsc(conversationId)
                .getLast()
                .id();
        enableFlow("dad");
        flowAnswers();
        int before = stub().received().size();

        chat.regenerate(dad, conversationId, event -> {});

        List<HermesRunCommand> commands =
                stub().received().subList(before, stub().received().size());
        assertThat(commands)
                .hasSize(4)
                .allSatisfy(command -> assertThat(command.instructions())
                        .endsWith("사용자가 바로 앞 질문에 대한 답을 다시 받기를 원한다. 앞의 답을 되풀이하지 말고 새로 답한다."));
        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)
                        .getLast()
                        .replacesMessageId())
                .isEqualTo(originalAnswer);
    }

    @Test
    @DisplayName("흐름으로 바꾼 대화도 이전 질문의 사진 자리를 재생성 입력에 넣는다")
    void flowConversationRegenerationInputKeepsPreviousImageSlots() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "사진 질문", "dad").conversationId();
        ChatMessage question =
                messages.findByConversationIdOrderByIdAsc(conversationId).getFirst();
        ChatAttachment attachment = attachmentRows.save(ChatAttachment.of(
                conversationId,
                dad.id(),
                "image.png",
                "image/png",
                1,
                Instant.now().plus(Duration.ofDays(1)), Instant.now()));
        attachment.nameStoredFile(attachment.id() + ".png");
        attachmentRows.save(attachment);
        attachments.attach(question.id(), conversationId, List.of(attachment.id()));
        enableFlow("dad");
        flowAnswers();
        int before = stub().received().size();

        chat.regenerate(dad, conversationId, event -> {});

        HermesRunCommand chief = stub()
                .received()
                .subList(before, stub().received().size())
                .stream()
                .filter(command -> command.input().contains("조사할 것과 만들 것을 나눈다"))
                .findFirst()
                .orElseThrow();
        assertThat(chief.input()).contains("[이번 메시지에 올린 사진]", attachment.id() + ".png", "사진 질문");
    }

    private void enableFlow(String agentCode) {
        Agent agent = agents.findByCode(agentCode).orElseThrow();
        agent.assignFlow(ResearchAndBuildFlow.NAME);
        agents.save(agent);
    }

    private void flowAnswers() {
        stub().willAnswer(command -> {
            if (command.input().contains("조사할 것과 만들 것을 나눈다")) {
                return result("chief", "{\"research\":\"자료\",\"build\":\"구현\"}");
            }
            if (command.input().contains("조사해")) {
                return result("research", "조사 결과");
            }
            if (command.input().contains("만든다")) {
                return result("engineer", "구현 결과");
            }
            return result("synthesizer", "합친 답");
        });
    }

    private CurrentUser member(String email, String profileName) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, Instant.now()));
        agents.save(Agent.of(
                profileName,
                profileName,
                profileName,
                "http://agent-runtime.test/p/" + profileName,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(), Instant.now()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private static HermesRunResult result(String runId, String output) {
        return HermesRunResult.of(runId, "session", "completed", output, "model", "provider", TokenUsage.empty());
    }
}
