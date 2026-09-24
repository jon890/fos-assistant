package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.MessageRole;
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
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ChatRegenerateTest {

    @Autowired ChatService chat;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired AgentModelSelector modelSelector;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachmentRows;
    @Autowired ConversationRepository conversations;
    @Autowired AttachmentService attachments;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired MemoryRepository memories;
    @Autowired HermesRunsClient hermes;

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
        modelOptions.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
    }

    @Test
    void 마지막_답을_다시_만들면_이전_답을_가리키고_질문은_늘지_않는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(result("first", "첫 답"), result("second", "새 답"));

        Long conversationId = chat.send(dad, null, "원래 질문", "dad").conversationId();
        List<ChatEvent> events = new ArrayList<>();
        chat.regenerate(dad, conversationId, events::add);

        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);
        assertThat(history).extracting(ChatMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT);
        assertThat(history.getLast().replacesMessageId()).isEqualTo(history.get(1).id());
        assertThat(stub().received()).extracting(HermesRunCommand::input)
                .containsExactly("원래 질문", "원래 질문");
        assertThat(stub().received().getLast().instructions())
                .endsWith("사용자가 바로 앞 질문에 대한 답을 다시 받기를 원한다. 앞의 답을 되풀이하지 말고 새로 답한다.");
        assertThat(events.getLast().type()).isEqualTo("done");
    }

    @Test
    void 다시_생성이_실패하면_이전_답_뒤에_새_메시지를_남기지_않는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        stub().willReturn(new HermesRunResult("failed", "session", "failed", "", null, null, "failed", TokenUsage.empty()));

        assertThatThrownBy(() -> chat.regenerate(dad, conversationId, event -> {}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_RUN_FAILED);

        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)).hasSize(2);
    }

    @Test
    void 답_없는_질문을_다시_시도하면_답은_새로_생기고_판을_가리키지_않는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(new HermesRunResult("failed", "session", "failed", "", null, null, "failed", TokenUsage.empty()));
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
        assertThat(stub().received().getLast().instructions()).isNull();
    }

    @Test
    void 마지막_질문을_고치면_새_질문은_이전_판을_가리키고_새_답은_가리키지_않는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(result("first", "첫 답"), result("edited", "고친 답"));
        Long conversationId = chat.send(dad, null, "원래 질문", "dad").conversationId();
        Long originalQuestion = messages.findByConversationIdOrderByIdAsc(conversationId).getFirst().id();

        chat.stream(dad, conversationId, "고친 질문", null, List.of(), originalQuestion, event -> {});

        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);
        assertThat(history).extracting(ChatMessage::content)
                .containsExactly("원래 질문", "첫 답", "고친 질문", "고친 답");
        assertThat(history.get(2).replacesMessageId()).isEqualTo(originalQuestion);
        assertThat(history.getLast().replacesMessageId()).isNull();
        assertThat(stub().received().getLast().input()).isEqualTo("고친 질문");
        assertThat(stub().received().getLast().instructions())
                .endsWith("사용자가 바로 앞 질문을 아래 글로 고쳤다. 고치기 전 질문과 그 답은 무시하고 고친 질문에 답한다.");
    }

    @Test
    void 앞선_질문은_고칠_수_없다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(result("first", "첫 답"), result("second", "둘째 답"));
        Long conversationId = chat.send(dad, null, "첫 질문", "dad").conversationId();
        Long firstQuestion = messages.findByConversationIdOrderByIdAsc(conversationId).getFirst().id();
        chat.send(dad, conversationId, "둘째 질문", null);

        assertThatThrownBy(() -> chat.stream(dad, conversationId, "고친 질문", null, List.of(), firstQuestion, event -> {}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.MESSAGE_NOT_LATEST);
    }

    @Test
    void 빈_대화는_다시_만들_답이_없다() {
        CurrentUser dad = member("dad@example.com", "dad");
        Agent agent = agents.findByCode("dad").orElseThrow();
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", agent.id()));

        assertThatThrownBy(() -> chat.regenerate(dad, conversation.id(), event -> {}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.MESSAGE_NOT_LATEST);
    }

    @Test
    void 다른_사용자의_대화는_없는_대화처럼_거절한다() {
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
    void 도는_재생성_중에는_둘째_재생성을_거절한다() throws Exception {
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
    void 도는_turn이_있는_대화는_재생성을_거절하고_새_실행을_만들지_않는다() throws Exception {
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
    void 첨부가_달린_질문은_수정할_수_없다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        ChatMessage question = messages.findByConversationIdOrderByIdAsc(conversationId).getFirst();
        ChatAttachment attachment = attachmentRows.save(ChatAttachment.of(
                conversationId, dad.id(), "image.png", "image/png", 1, Instant.now().plus(Duration.ofDays(1))));
        attachment.nameStoredFile(attachment.id() + ".png");
        attachmentRows.save(attachment);
        attachments.attach(question.id(), conversationId, List.of(attachment.id()));

        assertThatThrownBy(() -> chat.stream(
                dad, conversationId, "고친 질문", null, List.of(), question.id(), event -> {}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 다시_생성을_거듭하면_새_답이_직전_답을_가리킨다() {
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
    void 흐름_재생성은_모든_실행의_지시에_문구를_붙이고_답_판을_잇는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "질문", "dad").conversationId();
        Long originalAnswer = messages.findByConversationIdOrderByIdAsc(conversationId).getLast().id();
        enableFlow("dad");
        flowAnswers();
        int before = stub().received().size();

        chat.regenerate(dad, conversationId, event -> {});

        List<HermesRunCommand> commands = stub().received().subList(before, stub().received().size());
        assertThat(commands).hasSize(4).allSatisfy(command ->
                assertThat(command.instructions()).endsWith(
                        "사용자가 바로 앞 질문에 대한 답을 다시 받기를 원한다. 앞의 답을 되풀이하지 말고 새로 답한다."));
        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId).getLast().replacesMessageId())
                .isEqualTo(originalAnswer);
    }

    @Test
    void 흐름_수정은_모든_실행의_지시에_문구를_붙이고_질문_판을_잇는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "원래 질문", "dad").conversationId();
        Long originalQuestion = messages.findByConversationIdOrderByIdAsc(conversationId).getFirst().id();
        enableFlow("dad");
        flowAnswers();
        int before = stub().received().size();

        chat.stream(dad, conversationId, "고친 질문", null, List.of(), originalQuestion, event -> {});

        List<HermesRunCommand> commands = stub().received().subList(before, stub().received().size());
        assertThat(commands).hasSize(4).allSatisfy(command ->
                assertThat(command.instructions()).endsWith(
                        "사용자가 바로 앞 질문을 아래 글로 고쳤다. 고치기 전 질문과 그 답은 무시하고 고친 질문에 답한다."));
        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId).get(2).replacesMessageId())
                .isEqualTo(originalQuestion);
    }

    @Test
    void 흐름으로_바꾼_대화도_이전_질문의_사진_자리를_재생성_입력에_넣는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(result("first", "첫 답"));
        Long conversationId = chat.send(dad, null, "사진 질문", "dad").conversationId();
        ChatMessage question = messages.findByConversationIdOrderByIdAsc(conversationId).getFirst();
        ChatAttachment attachment = attachmentRows.save(ChatAttachment.of(
                conversationId, dad.id(), "image.png", "image/png", 1, Instant.now().plus(Duration.ofDays(1))));
        attachment.nameStoredFile(attachment.id() + ".png");
        attachmentRows.save(attachment);
        attachments.attach(question.id(), conversationId, List.of(attachment.id()));
        enableFlow("dad");
        flowAnswers();
        int before = stub().received().size();

        chat.regenerate(dad, conversationId, event -> {});

        HermesRunCommand chief = stub().received().subList(before, stub().received().size()).stream()
                .filter(command -> command.input().contains("조사할 것과 만들 것을 나눈다"))
                .findFirst().orElseThrow();
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
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        Agent agent = agents.save(Agent.of(
                profileName, profileName, profileName, "http://agent-runtime.test/p/" + profileName,
                "anthropic", "example-model-large", CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, user.id()));
        modelSelector.seedFirst(agent, new ModelOption("anthropic", "example-model-large"));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.familyId(), user.role());
    }

    private static HermesRunResult result(String runId, String output) {
        return HermesRunResult.of(runId, "session", "completed", output, "model", "provider", TokenUsage.empty());
    }
}
