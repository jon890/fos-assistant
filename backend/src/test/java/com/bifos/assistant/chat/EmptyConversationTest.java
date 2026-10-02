package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
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
import java.time.Instant;
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

/** 사진을 먼저 올리거나 모델을 먼저 고르려고 메시지 없이 만드는 대화와, 그 대화의 제목을 첫 메시지가 정하는 것을 확인한다. */
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

    @BeforeEach
    void setUp() {
        ((StubHermesRunsClient) hermes).reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("쓸 수 있는 에이전트로 제목이 빈 대화를 만든다")
    void createsEmptyTitleConversationWithUsableAgent() {
        CurrentUser dad = member("dad@example.com");
        agentOf(dad, "dad");

        Conversation created = chat.startEmpty(dad, "dad");

        Conversation stored = conversations.findById(created.id()).orElseThrow();
        assertThat(stored.userId()).isEqualTo(dad.id());
        assertThat(stored.title()).isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(created.id())).isEmpty();
    }

    @Test
    @DisplayName("첫 메시지가 제목을 정하고 둘째 메시지는 바꾸지 않는다")
    void firstMessageSetsTitleAndSecondDoesNotChangeIt() {
        CurrentUser dad = member("dad@example.com");
        agentOf(dad, "dad");
        ((StubHermesRunsClient) hermes)
                .willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        Long conversationId = chat.startEmpty(dad, "dad").id();

        chat.send(dad, conversationId, "  이 사진   정리해 줘 ", null);
        assertThat(conversations.findById(conversationId).orElseThrow().title()).isEqualTo("이 사진 정리해 줘");

        chat.send(dad, conversationId, "다른 이야기", null);
        assertThat(conversations.findById(conversationId).orElseThrow().title()).isEqualTo("이 사진 정리해 줘");
    }

    @Test
    @DisplayName("읽을 수 없거나 꺼진 에이전트는 새 대화를 만들 때와 같은 오류다")
    void unreadableOrDisabledAgentGivesSameErrorAsCreatingNewConversation() {
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
        assertThat(chat.conversationsOf(dad, null, 100).items()).isEmpty();
    }

    @Test
    @DisplayName("흐름이 붙은 에이전트도 빈 대화를 만든다")
    void createsEmptyConversationForFlowAgentToo() {
        CurrentUser dad = member("dad@example.com");
        Agent flowAgent = agentOf(dad, "flowed");
        flowAgent.assignFlow("research-and-build");
        agents.save(flowAgent);

        chat.startEmpty(dad, "flowed");

        assertThat(chat.conversationsOf(dad, null, 100).items()).singleElement().satisfies(it -> {
            assertThat(it.agentId()).isEqualTo(flowAgent.id());
            assertThat(it.title()).isEmpty();
        });
    }

    private CurrentUser member(String email) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, Instant.now()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Agent agentOf(CurrentUser owner, String code) {
        Agent saved = agents.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(), Instant.now()));
        return saved;
    }

    private static void assertSameCode(Runnable empty, Runnable firstMessage, ErrorCode expected) {
        assertThatThrownBy(empty::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
        assertThatThrownBy(firstMessage::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
