package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.dto.AgentLabel;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.dto.DelegationLine;
import com.bifos.assistant.chat.turn.application.dto.MentionMarks;
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
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code @} 맡기기를 없앤 뒤의 동작을 본다(docs/features/assistant-hub/spec.md 의 REQ6).
 *
 * <p>보내기의 {@code delegateTo} 는 거절하고, 그 전에 남은 맡김은 지금처럼 대화에 보이고 대화 에이전트의 다음 turn 에 한 번 들어간다.
 * 지난 맡김은 이제 API 로 만들 수 없어 대화의 실행 줄 하나를 다른 에이전트의 것으로 바꿔 흉내 낸다. 대화의 에이전트가 아닌 루트
 * 실행이 맡김이라는 판정({@code MentionLines})과 같은 꼴이다.
 */
@BackendIntegrationTest
class MentionDelegationTest {

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

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser dad;
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
        primaries.make(dad, agents.save(agent("dad", "비서", user.id())));
        shopper = agents.save(agent("shopper", "쇼핑 담당", user.id()));
    }


    @Test
    @DisplayName("보내기에 맡길 에이전트가 있으면 아무것도 저장하거나 실행하지 않고 거절한다")
    void rejectsDelegateTo() {
        Long conversationId = chat.send(dad, null, "안녕", "dad").conversationId();
        long saved = messages.count();
        int runs = stub().received().size();

        assertRejected(
            () -> chat.send(dad, conversationId, "운동화 찾아줘", null, List.of(), "shopper"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> chat.stream(dad, conversationId, "운동화 찾아줘", null, List.of(), "shopper", null, event -> {
        }), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> chat.send(dad, null, "운동화 찾아줘", null, List.of(), "shopper"), ErrorCode.VALIDATION_FAILED);

        assertThat(messages.count()).isEqualTo(saved);
        assertThat(stub().received()).hasSize(runs);
    }


    @Test
    @DisplayName("지난 맡김은 질문과 답에 표시가 남고 다음 turn 에 한 번만 들어가며 다시 만들지 못한다")
    void pastMentionStaysVisibleAndCarriedOnce() {
        Long conversationId = chat.send(dad, null, "다음 주에 등산 가", "dad").conversationId();
        chat.send(dad, conversationId, "운동화 찾아줘", null);
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);
        ChatMessage question = history.get(2);
        Long mentionId = history.getLast().executionId();
        jdbc.update("update agent_execution set agent_id = ? where id = ?", shopper.id(), mentionId);

        MentionMarks marks = chat.mentionMarks(dad, conversationId);
        AgentLabel label = AgentLabel.of(shopper);
        assertThat(marks.questions())
            .containsOnlyKeys(question.id())
            .containsEntry(question.id(), new DelegationLine(mentionId, null, ExecutionStatus.SUCCEEDED, label));
        assertThat(marks.answers()).containsOnlyKeys(mentionId);
        assertRejected(() -> chat.regenerate(dad, conversationId, event -> {
        }), ErrorCode.VALIDATION_FAILED);

        chat.send(dad, conversationId, "그중 제일 싼 걸로", null);

        assertThat(stub().received().getLast().input())
            .contains("[사용자가 @ 로 다른 에이전트에게 바로 맡긴 일]")
            .contains("[출처: @ 로 맡긴 일, 에이전트: 쇼핑 담당, 실행 번호: " + mentionId + ", 상태: SUCCEEDED, 끝난 시각: ")
            .contains("사용자: 운동화 찾아줘")
            .endsWith("[사용자 메시지]\n그중 제일 싼 걸로");

        chat.send(dad, conversationId, "고마워", null);

        assertThat(stub().received().getLast().input())
            .doesNotContain("[사용자가 @ 로 다른 에이전트에게 바로 맡긴 일]")
            .endsWith("고마워");
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
