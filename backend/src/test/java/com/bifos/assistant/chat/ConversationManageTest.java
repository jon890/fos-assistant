package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
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
import org.junit.jupiter.api.DisplayName;
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

    @Autowired
    ChatService chat;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    AgentRepository agents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    AppUserRepository users;

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
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
    }

    private CurrentUser member(String name) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, UserRole.MEMBER));
        agents.save(Agent.of(
                name,
                name,
                name,
                "http://agent-runtime.test/p/" + name,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private void successfulAnswer() {
        stub().willReturn(HermesRunResult.of(
                "run-one", "session-one", "completed", "답", "example-model-large", "anthropic", TokenUsage.empty()));
    }

    private static void notFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("이름을 바꾸고 공백과 길이를 검사한다")
    void renamesAndValidatesBlankAndLength() {
        CurrentUser dad = member("manage-dad");
        successfulAnswer();
        Long id = chat.send(dad, null, "첫 질문", "manage-dad").conversationId();

        chat.rename(dad, id, "  새 이름  ");
        assertThat(chat.conversationsOf(dad))
                .singleElement()
                .satisfies(it -> assertThat(it.title()).isEqualTo("새 이름"));
        assertThatThrownBy(() -> chat.rename(dad, id, "   "))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> chat.rename(dad, id, "가".repeat(201)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("지운 대화는 읽거나 이어 보낼 수 없지만 실행은 남는다")
    void deletedConversationCannotBeReadOrContinuedButRunsRemain() {
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
    @DisplayName("남의 대화는 이름을 바꾸거나 지울 수 없다")
    void othersConversationCannotBeRenamedOrDeleted() {
        CurrentUser dad = member("manage-dad");
        CurrentUser kid = member("manage-kid");
        successfulAnswer();
        Long id = chat.send(dad, null, "첫 질문", "manage-dad").conversationId();

        notFound(() -> chat.rename(kid, id, "남의 이름"));
        notFound(() -> chat.delete(kid, id));
        assertThat(chat.conversationsOf(dad)).singleElement();
    }

    @Test
    @DisplayName("스트림은 실행을 만들자마자 번호를 보낸다")
    void streamSendsRunIdAsSoonAsRunIsCreated() {
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
    @DisplayName("provider가 막혀 실패해도 실패한 실행의 번호를 보낸다")
    void sendsFailedRunIdEvenWhenProviderBlockedFailure() {
        CurrentUser dad = member("manage-dad");
        stub().willReturn(new HermesRunResult(
                "run-blocked",
                null,
                "failed",
                null,
                "example-model-large",
                "anthropic",
                HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " no account",
                TokenUsage.empty()));
        List<ChatEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> chat.stream(dad, null, "첫 질문", "manage-dad", events::add))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PROVIDER_BLOCKED);
        assertThat(events).singleElement().satisfies(started -> {
            assertThat(started.type()).isEqualTo("started");
            assertThat(executions.findById(started.executionId()).orElseThrow().status())
                    .isEqualTo(ExecutionStatus.FAILED);
        });
    }

    @Test
    @DisplayName("실행 도중 지우거나 이름을 바꿔도 끝난 뒤에 남는다")
    void deleteOrRenameDuringRunSurvivesAfterRunEnds() {
        CurrentUser dad = member("manage-dad");
        successfulAnswer();
        Long deletedId = chat.send(dad, null, "첫 질문", "manage-dad").conversationId();
        stub().willReturn(HermesRunResult.of(
                "run-two", "session-two", "completed", "둘째 답", "example-model-large", "anthropic", TokenUsage.empty()));
        stub().beforeAwait(() -> chat.delete(dad, deletedId));
        chat.send(dad, deletedId, "이어 보내기", "manage-dad");
        assertThat(conversations.findById(deletedId).orElseThrow().deletedAt()).isNotNull();
        assertThat(conversations.findById(deletedId).orElseThrow().hermesSessionId())
                .isEqualTo("session-two");

        stub().willReturn(HermesRunResult.of(
                "run-three",
                "session-three",
                "completed",
                "셋째 답",
                "example-model-large",
                "anthropic",
                TokenUsage.empty()));
        stub().beforeAwait(() ->
                chat.rename(dad, chat.conversationsOf(dad).getFirst().id(), "바뀐 이름"));
        Long renamedId = chat.send(dad, null, "다른 질문", "manage-dad").conversationId();
        assertThat(conversations.findById(renamedId).orElseThrow().title()).isEqualTo("바뀐 이름");
        assertThat(conversations.findById(renamedId).orElseThrow().hermesSessionId())
                .isEqualTo("session-three");
    }
}
