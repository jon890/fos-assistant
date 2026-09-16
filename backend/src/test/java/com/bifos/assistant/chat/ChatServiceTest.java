package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.credential.domain.CostMode;
import com.bifos.assistant.credential.domain.HermesProfileBinding;
import com.bifos.assistant.credential.infra.HermesProfileBindingRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;

@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ChatServiceTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired ChatService chat;
    @Autowired AppUserRepository users;
    @Autowired HermesProfileBindingRepository bindings;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired HermesRunsClient hermes;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void reset() {
        stub().reset();
        executions.deleteAll();
        messages.deleteAll();
        bindings.deleteAll();
        users.deleteAll();
    }

    private CurrentUser member(String email, String profileName) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        if (profileName != null) {
            bindings.save(
                    HermesProfileBinding.of(
                            user.id(), profileName, "anthropic", "claude-opus-5", CostMode.SUBSCRIPTION));
        }
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.role());
    }

    @Test
    void routes_the_turn_to_the_caller_own_profile_and_records_what_it_used() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-1",
                                "sess-1",
                                "completed",
                                "저녁은 김치찌개가 좋겠어요.",
                                "claude-opus-5",
                                "anthropic",
                                new TokenUsage(120L, 80L, 40L, 160L)));

        ChatTurn turn = chat.send(dad, null, "오늘 저녁 뭐 먹을까?");

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.profileName()).isEqualTo("dad");
            assertThat(command.input()).isEqualTo("오늘 저녁 뭐 먹을까?");
            assertThat(command.sessionId()).isNull();
        });
        assertThat(turn.assistantText()).isEqualTo("저녁은 김치찌개가 좋겠어요.");

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.userId()).isEqualTo(dad.id());
        assertThat(execution.profileName()).isEqualTo("dad");
        assertThat(execution.provider()).isEqualTo("anthropic");
        assertThat(execution.model()).isEqualTo("claude-opus-5");
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.inputTokens()).isEqualTo(120);
        assertThat(execution.cachedInputTokens()).isEqualTo(80);
        assertThat(execution.outputTokens()).isEqualTo(40);
        assertThat(execution.totalTokens()).isEqualTo(160);
        assertThat(execution.costMode()).isEqualTo(CostMode.SUBSCRIPTION);
        assertThat(execution.estimatedCostMicros()).isNull();
        assertThat(execution.latencyMs()).isGreaterThanOrEqualTo(0);

        assertThat(messages.findByConversationIdOrderByIdAsc(turn.conversationId()))
                .extracting(it -> it.role())
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
    }

    @Test
    void continues_the_same_hermes_session_on_the_next_turn() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn first = chat.send(dad, null, "안녕");

        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-2", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        chat.send(dad, first.conversationId(), "하나 더");

        assertThat(stub().received().get(1).sessionId()).isEqualTo("sess-1");
    }

    @Test
    void refuses_a_member_with_no_profile_bound_and_never_calls_the_runtime() {
        CurrentUser kid = member("kid@example.com", null);

        assertThatThrownBy(() -> chat.send(kid, null, "숙제 도와줘"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_BINDING_MISSING);
        assertThat(stub().received()).isEmpty();
    }

    @Test
    void refuses_to_read_another_member_conversation() {
        CurrentUser dad = member("dad@example.com", "dad");
        CurrentUser mom = member("mom@example.com", "mom");
        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn dadTurn = chat.send(dad, null, "비밀 얘기");

        assertThatThrownBy(() -> chat.history(mom, dadTurn.conversationId()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void records_a_failed_run_so_the_usage_view_still_shows_it() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕")).isInstanceOf(ApiException.class);

        var recorded = executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10));
        assertThat(recorded).singleElement().satisfies(execution -> {
            assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(execution.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        });
    }
}
