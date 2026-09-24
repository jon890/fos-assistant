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
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** 사진을 먼저 올리려고 메시지 없이 만드는 대화와, 그 대화의 제목을 첫 메시지가 정하는 것을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmptyConversationTest.StubRuntime.class)
class EmptyConversationTest {

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
    @Autowired AgentRepository agents;
    @Autowired AgentModelSelector modelSelector;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired HermesRunsClient hermes;

    @BeforeEach
    void 준비한다() {
        ((StubHermesRunsClient) hermes).reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        modelOptions.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }

    @Test
    void 쓸_수_있는_에이전트로_제목이_빈_대화를_만든다() {
        CurrentUser dad = member("dad@example.com");
        agentOf(dad, "dad");

        Conversation created = chat.startEmpty(dad, "dad");

        Conversation stored = conversations.findById(created.id()).orElseThrow();
        assertThat(stored.userId()).isEqualTo(dad.id());
        assertThat(stored.title()).isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(created.id())).isEmpty();
    }

    @Test
    void 첫_메시지가_제목을_정하고_둘째_메시지는_바꾸지_않는다() {
        CurrentUser dad = member("dad@example.com");
        agentOf(dad, "dad");
        ((StubHermesRunsClient) hermes).willReturn(
                HermesRunResult.of("run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        Long conversationId = chat.startEmpty(dad, "dad").id();

        chat.send(dad, conversationId, "  이 사진   정리해 줘 ", null);
        assertThat(conversations.findById(conversationId).orElseThrow().title()).isEqualTo("이 사진 정리해 줘");

        chat.send(dad, conversationId, "다른 이야기", null);
        assertThat(conversations.findById(conversationId).orElseThrow().title()).isEqualTo("이 사진 정리해 줘");
    }

    @Test
    void 읽을_수_없거나_꺼진_에이전트는_새_대화를_만들_때와_같은_오류다() {
        CurrentUser dad = member("dad@example.com");
        CurrentUser kid = member("kid@example.com");
        agentOf(kid, "kid");
        Agent off = agentOf(dad, "off");
        off.changeAccess(false, AgentVisibility.PRIVATE, dad.id());
        agents.save(off);

        assertSameCode(
                () -> chat.startEmpty(dad, "kid"), () -> chat.send(dad, null, "안녕", "kid"), ErrorCode.AGENT_NOT_FOUND);
        assertSameCode(
                () -> chat.startEmpty(dad, "off"), () -> chat.send(dad, null, "안녕", "off"), ErrorCode.AGENT_DISABLED);
        assertThat(conversations.findByUserIdOrderByUpdatedAtDesc(dad.id())).isEmpty();
    }

    @Test
    void 흐름이_붙은_에이전트에는_빈_대화를_만들지_않는다() {
        CurrentUser dad = member("dad@example.com");
        Agent flowAgent = agentOf(dad, "flowed");
        flowAgent.assignFlow("research-and-build");
        agents.save(flowAgent);

        assertThatThrownBy(() -> chat.startEmpty(dad, "flowed"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(conversations.findByUserIdOrderByUpdatedAtDesc(dad.id())).isEmpty();
    }

    private CurrentUser member(String email) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.familyId(), user.role());
    }

    private Agent agentOf(CurrentUser owner, String code) {
        Agent saved = agents.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                "anthropic",
                "claude-opus-5",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id()));
        modelSelector.seedFirst(saved, new ModelOption("anthropic", "claude-opus-5"));
        return saved;
    }

    private static void assertSameCode(Runnable empty, Runnable firstMessage, ErrorCode expected) {
        assertThatThrownBy(empty::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
        assertThatThrownBy(firstMessage::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
