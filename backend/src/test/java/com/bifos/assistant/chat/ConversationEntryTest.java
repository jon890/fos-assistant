package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.runtime.domain.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestPrimaryAgents;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 새 대화는 늘 요청자의 기본 비서와 시작한다(docs/features/assistant-hub/spec.md 의 REQ4).
 *
 * <p>기본 비서는 요청자의 기본 profile 을 가리키는 에이전트다. 테스트 사용자 {@code dad@example.com} 의 기본 profile 은
 * {@code dad} 다.
 */
@BackendIntegrationTest
class ConversationEntryTest {

    @Autowired
    TestPrimaryAgents primaries;

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

    private CurrentUser dad;
    private Agent primary;


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
        agents.save(agent("shopper", "쇼핑 담당", user.id()));
    }


    @Test
    @DisplayName("에이전트 코드가 없거나 기본 비서의 코드면 새 대화와 빈 대화가 기본 비서로 생긴다")
    void startsWithPrimary() {
        Long sent = chat.send(dad, null, "안녕", null).conversationId();
        Long named = chat.send(dad, null, "안녕", "dad").conversationId();
        Conversation empty = chat.startEmpty(dad, null);
        Conversation emptyNamed = chat.startEmpty(dad, "dad");

        assertThat(conversations.findAllById(List.of(sent, named, empty.id(), emptyNamed.id())))
            .extracting(Conversation::agentId)
            .containsOnly(primary.id())
            .hasSize(4);
        assertThat(stub().received()).extracting(command -> command.profileName()).containsOnly("dad");
    }


    @Test
    @DisplayName("다른 에이전트로 새 대화를 열면 아무것도 저장하지 않고 거절한다")
    void rejectsOtherAgentForNewConversation() {
        assertRejected(() -> chat.send(dad, null, "운동화 찾아줘", "shopper"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> chat.startEmpty(dad, "shopper"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> chat.stream(dad, null, "운동화 찾아줘", "shopper", event -> {
        }), ErrorCode.VALIDATION_FAILED);

        assertThat(conversations.count()).isZero();
        assertThat(stub().received()).isEmpty();
    }


    @Test
    @DisplayName("기본 비서가 꺼졌으면 AGENT_DISABLED, 없으면 AGENT_NOT_FOUND 로 새 대화를 거절한다")
    void rejectsWhenPrimaryUnavailable() {
        primary.changeAccess(false, AgentVisibility.PRIVATE, dad.id());
        agents.save(primary);
        assertRejected(() -> chat.send(dad, null, "안녕", null), ErrorCode.AGENT_DISABLED);
        assertRejected(() -> chat.startEmpty(dad, null), ErrorCode.AGENT_DISABLED);

        AppUser kidRow = users.save(AppUser.of("kid@example.com", "kid", 1L, UserRole.MEMBER));
        CurrentUser kid = new CurrentUser(
            kidRow.id(), kidRow.email(), kidRow.displayName(), kidRow.groupId(), kidRow.role());
        assertRejected(() -> chat.send(kid, null, "안녕", null), ErrorCode.AGENT_NOT_FOUND);

        assertThat(conversations.count()).isZero();
    }


    @Test
    @DisplayName("이어 쓰는 대화는 요청의 에이전트 코드와 상관없이 그 대화의 에이전트로 돈다")
    void continuingIgnoresAgentCode() {
        Long conversationId = chat.send(dad, null, "안녕", null).conversationId();

        chat.send(dad, conversationId, "하나 더", "shopper");

        assertThat(stub().received().getLast().profileName()).isEqualTo("dad");
        assertThat(conversations.findById(conversationId).orElseThrow().agentId()).isEqualTo(primary.id());
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
