package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.credential.domain.CostMode;
import com.bifos.assistant.credential.domain.CredentialScope;
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
import com.bifos.assistant.workspace.domain.Workspace;
import com.bifos.assistant.workspace.domain.WorkspaceVisibility;
import com.bifos.assistant.workspace.infra.WorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    @TempDir static Path workspaceRoot;

    @DynamicPropertySource
    static void pointAtTheMountRoot(DynamicPropertyRegistry registry) {
        registry.add("assistant.workspace.root", () -> workspaceRoot.toString());
    }

    @Autowired ChatService chat;
    @Autowired AppUserRepository users;
    @Autowired HermesProfileBindingRepository bindings;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired HermesRunsClient hermes;
    @Autowired WorkspaceRepository workspaces;

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
        workspaces.deleteAll();
    }

    private Workspace familyWorkspace(String code, String guideBody) throws IOException {
        Path dir = workspaceRoot.resolve(code);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENTS.md"), guideBody);
        return workspaces.save(Workspace.of(code, code, code, WorkspaceVisibility.FAMILY, null));
    }

    private Workspace privateWorkspace(String code, Long ownerUserId, String guideBody) throws IOException {
        Path dir = workspaceRoot.resolve(code);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENTS.md"), guideBody);
        return workspaces.save(Workspace.of(code, code, code, WorkspaceVisibility.PRIVATE, ownerUserId));
    }

    private CurrentUser member(String email, String profileName) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        if (profileName != null) {
            bindings.save(
                    HermesProfileBinding.of(
                            user.id(),
                            profileName,
                            "http://hermes:8642/p/" + profileName,
                            "anthropic",
                            "claude-opus-5",
                            CostMode.SUBSCRIPTION,
                            CredentialScope.SHARED_HOUSEHOLD));
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

        ChatTurn turn = chat.send(dad, null, "오늘 저녁 뭐 먹을까?", null);

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.profileName()).isEqualTo("dad");
            assertThat(command.apiBaseUrl()).isEqualTo("http://hermes:8642/p/dad");
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
        ChatTurn first = chat.send(dad, null, "안녕", null);

        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-2", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        chat.send(dad, first.conversationId(), "하나 더", null);

        assertThat(stub().received().get(1).sessionId()).isEqualTo("sess-1");
    }

    @Test
    void records_the_bound_model_when_the_run_only_echoes_the_profile_name() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));

        ChatTurn turn = chat.send(dad, null, "안녕", null);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.model()).isEqualTo("claude-opus-5");
        assertThat(execution.provider()).isEqualTo("anthropic");
    }

    @Test
    void refuses_a_member_with_no_profile_bound_and_never_calls_the_runtime() {
        CurrentUser kid = member("kid@example.com", null);

        assertThatThrownBy(() -> chat.send(kid, null, "숙제 도와줘", null))
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
        ChatTurn dadTurn = chat.send(dad, null, "비밀 얘기", null);

        assertThatThrownBy(() -> chat.history(mom, dadTurn.conversationId()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void records_a_failed_run_so_the_usage_view_still_shows_it() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", null)).isInstanceOf(ApiException.class);

        var recorded = executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10));
        assertThat(recorded).singleElement().satisfies(execution -> {
            assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(execution.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        });
    }

    @Test
    void 영역을_주면_그_영역의_AGENTSMD_가_instructions_로_넘어간다() throws IOException {
        CurrentUser dad = member("dad@example.com", "dad");
        familyWorkspace("home", "이 영역의 규칙: 존댓말을 쓴다.");
        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));

        chat.send(dad, null, "안녕", "home");

        assertThat(stub().received()).singleElement().satisfies(command ->
                assertThat(command.instructions()).contains("이 영역의 규칙: 존댓말을 쓴다."));
    }

    @Test
    void 영역을_주지_않으면_instructions_가_비어_있다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));

        chat.send(dad, null, "안녕", null);

        assertThat(stub().received()).singleElement().satisfies(command ->
                assertThat(command.instructions()).isNull());
    }

    @Test
    void 남의_개인_영역으로_대화를_시작하면_WORKSPACE_NOT_FOUND_다() throws IOException {
        CurrentUser dad = member("dad@example.com", "dad");
        CurrentUser mom = member("mom@example.com", "mom");
        privateWorkspace("mom-journal", mom.id(), "엄마만 보는 것");

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", "mom-journal"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND);
        assertThat(stub().received()).isEmpty();
    }

    @Test
    void 이어지는_대화는_요청이_영역을_다시_주지_않아도_첫_영역을_유지한다() throws IOException {
        CurrentUser dad = member("dad@example.com", "dad");
        familyWorkspace("home", "이 영역의 규칙: 존댓말을 쓴다.");
        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn first = chat.send(dad, null, "안녕", "home");

        stub()
                .willReturn(
                        new HermesRunResult(
                                "run-2", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        chat.send(dad, first.conversationId(), "하나 더", null);

        assertThat(stub().received().get(1).instructions()).contains("이 영역의 규칙: 존댓말을 쓴다.");
    }
}
