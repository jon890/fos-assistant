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
import com.bifos.assistant.agent.infra.ProviderStateRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(ConversationManageTest.StubRuntime.class)
class ConversationManageTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired ChatService chat;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired AgentRepository agents;
    @Autowired AgentModelSelector modelSelector;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired ProviderStateRepository providerStates;
    @Autowired MemoryRepository memories;
    @Autowired AppUserRepository users;
    @Autowired HermesRunsClient hermes;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void reset() {
        stub().reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        providerStates.deleteAll();
        modelOptions.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
    }

    private CurrentUser member(String name) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, UserRole.MEMBER));
        Agent agent = agents.save(Agent.of(name, name, name,
                "http://agent-runtime.test/p/" + name, "anthropic", "example-model-large",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, user.id()));
        modelSelector.seedFirst(agent, new ModelOption("anthropic", "example-model-large"));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.familyId(), user.role());
    }

    private void successfulAnswer() {
        stub().willReturn(HermesRunResult.of("run-one", "session-one", "completed", "답",
                "example-model-large", "anthropic", TokenUsage.empty()));
    }

    private static void notFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void 이름을_바꾸고_공백과_길이를_검사한다() {
        CurrentUser dad = member("manage-dad");
        successfulAnswer();
        Long id = chat.send(dad, null, "첫 질문", "manage-dad").conversationId();

        chat.rename(dad, id, "  새 이름  ");
        assertThat(chat.conversationsOf(dad)).singleElement()
                .satisfies(it -> assertThat(it.title()).isEqualTo("새 이름"));
        assertThatThrownBy(() -> chat.rename(dad, id, "   "))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> chat.rename(dad, id, "가".repeat(201)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 지운_대화는_읽거나_이어_보낼_수_없지만_실행은_남는다() {
        CurrentUser dad = member("manage-dad");
        successfulAnswer();
        ChatTurn turn = chat.send(dad, null, "첫 질문", "manage-dad");
        chat.delete(dad, turn.conversationId());

        assertThat(chat.conversationsOf(dad)).isEmpty();
        notFound(() -> chat.history(dad, turn.conversationId()));
        notFound(() -> chat.send(dad, turn.conversationId(), "이어 보내기", "manage-dad"));
        assertThat(executions.findById(turn.executionId())).isPresent();
    }

    @Test
    void 남의_대화는_이름을_바꾸거나_지울_수_없다() {
        CurrentUser dad = member("manage-dad");
        CurrentUser kid = member("manage-kid");
        successfulAnswer();
        Long id = chat.send(dad, null, "첫 질문", "manage-dad").conversationId();

        notFound(() -> chat.rename(kid, id, "남의 이름"));
        notFound(() -> chat.delete(kid, id));
        assertThat(chat.conversationsOf(dad)).singleElement();
    }

    @Test
    void 스트림은_실행을_만들자마자_번호를_보낸다() {
        CurrentUser dad = member("manage-dad");
        successfulAnswer();
        List<ChatEvent> events = new ArrayList<>();
        chat.stream(dad, null, "첫 질문", "manage-dad", events::add);

        assertThat(events.getFirst().type()).isEqualTo("started");
        ChatEvent started = events.getFirst();
        ChatEvent done = events.getLast();
        assertThat(started.conversationId()).isEqualTo(done.conversationId());
        assertThat(started.executionId()).isEqualTo(done.executionId());
    }

    @Test
    void provider를_넘어가면_새_실행_번호를_다시_보낸다() {
        CurrentUser dad = member("manage-dad");
        Agent agent = agents.findByCode("manage-dad").orElseThrow();
        modelSelector.replace(agent, List.of(
                new ModelOption("anthropic", "example-model-large"),
                new ModelOption("nvidia", "example-model-small")));
        stub().willReturnInOrder(
                new HermesRunResult("run-blocked", null, "failed", null,
                        "example-model-large", "anthropic",
                        HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " no account", TokenUsage.empty()),
                HermesRunResult.of("run-good", "session-two", "completed", "답",
                        "example-model-small", "nvidia", TokenUsage.empty()));

        List<ChatEvent> events = new ArrayList<>();
        chat.stream(dad, null, "첫 질문", "manage-dad", events::add);

        List<ChatEvent> started = events.stream().filter(it -> "started".equals(it.type())).toList();
        assertThat(started).hasSize(2);
        assertThat(started.getFirst().executionId()).isNotEqualTo(started.getLast().executionId());
        assertThat(started.getLast().executionId()).isEqualTo(events.getLast().executionId());
    }

    @Test
    void 모델이_없어도_실패한_실행의_번호를_보낸다() {
        CurrentUser dad = member("manage-dad");
        modelOptions.deleteAll();
        List<ChatEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> chat.stream(dad, null, "첫 질문", "manage-dad", events::add))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo(ErrorCode.NO_MODEL_AVAILABLE);
        assertThat(events).singleElement().satisfies(started -> {
            assertThat(started.type()).isEqualTo("started");
            assertThat(executions.findById(started.executionId()).orElseThrow().status())
                    .isEqualTo(ExecutionStatus.FAILED);
        });
    }

    @Test
    void 실행_도중_지우거나_이름을_바꿔도_끝난_뒤에_남는다() {
        CurrentUser dad = member("manage-dad");
        successfulAnswer();
        Long deletedId = chat.send(dad, null, "첫 질문", "manage-dad").conversationId();
        stub().beforeAwait(() -> chat.delete(dad, deletedId));
        chat.send(dad, deletedId, "이어 보내기", "manage-dad");
        assertThat(conversations.findById(deletedId).orElseThrow().deletedAt()).isNotNull();
        assertThat(conversations.findById(deletedId).orElseThrow().hermesSessionId())
                .isEqualTo("session-one");

        stub().beforeAwait(() -> chat.rename(dad, chat.conversationsOf(dad).getFirst().id(), "바뀐 이름"));
        Long renamedId = chat.send(dad, null, "다른 질문", "manage-dad").conversationId();
        assertThat(conversations.findById(renamedId).orElseThrow().title()).isEqualTo("바뀐 이름");
    }
}
