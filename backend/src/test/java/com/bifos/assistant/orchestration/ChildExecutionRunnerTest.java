package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.ChildExecutionRunner;
import com.bifos.assistant.orchestration.domain.ChildResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
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

/** 자식 실행 하나가 부모의 경계를 그대로 물려받는 것을 고정한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(ChildExecutionRunnerTest.StubRuntime.class)
class ChildExecutionRunnerTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired ChildExecutionRunner children;
    @Autowired ExecutionRecorder recorder;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired MemoryService memories;
    @Autowired MemoryRepository memoryRepository;
    @Autowired HermesRunsClient hermes;

    /** 이 검사가 쓰는 에이전트와 구성원이다. 다른 검사 클래스와 겹치지 않는 이름으로 둔다. */
    private static final List<String> MY_AGENTS = List.of("child-dad", "child-mom");
    private static final List<String> MY_EMAILS =
            List.of("child-dad@example.com", "child-mom@example.com");

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    /**
     * 이 검사만의 자료를 지운다.
     *
     * <p>에이전트와 사용자를 통째로 지우지 않는다. 같은 H2 를 다른 검사 클래스와 함께 쓰므로, 남의 줄을
     * 지우면 그쪽이 다시 만들다가 profile 이름이 겹쳐 실패한다.
     */
    @BeforeEach
    void reset() {
        stub().reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        memoryRepository.deleteAll();
        MY_AGENTS.forEach(code -> agents.findByCode(code).ifPresent(agents::delete));
        MY_EMAILS.forEach(email -> users.findByEmail(email).ifPresent(users::delete));
    }

    private CurrentUser member(String email, String agentCode) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        if (agentCode != null) {
            agents.save(Agent.of(
                    agentCode,
                    agentCode,
                    agentCode,
                    "http://agent-runtime.test/p/" + agentCode,
                    "anthropic",
                    "claude-opus-5",
                    CostMode.SUBSCRIPTION,
                    CredentialScope.SHARED_HOUSEHOLD,
                    AgentVisibility.PRIVATE,
                    user.id()));
        }
        return new CurrentUser(
                user.id(), user.email(), user.displayName(), user.familyId(), user.role());
    }

    private Conversation conversationOf(CurrentUser user, String agentCode) {
        Agent agent = agents.findByCode(agentCode).orElseThrow();
        return conversations.save(Conversation.startedBy(user.id(), "제목", agent.id()));
    }

    /** 부모 실행 하나를 뿌리로 만든다. */
    private AgentExecution parentOf(CurrentUser user, Conversation conversation, String agentCode) {
        return recorder.start(
                user, conversation, agents.findByCode(agentCode).orElseThrow(), null, null, 0L);
    }

    private static HermesRunResult completed(String runId, String output) {
        return new HermesRunResult(
                runId, "sess-child", "completed", output, "claude-opus-5", "anthropic",
                new TokenUsage(30L, 10L, 5L, 35L));
    }

    @Test
    void 자식_실행이_부모를_가리키며_SUCCEEDED로_남는다() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        Conversation conversation = conversationOf(dad, "child-dad");
        AgentExecution parent = parentOf(dad, conversation, "child-dad");
        stub().willReturn(completed("run-child", "조사 결과"));

        ChildResult result = children.run(dad, conversation, parent, "child-dad", "이것을 조사해라");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).isEqualTo("조사 결과");
        AgentExecution child = executions.findById(result.executionId()).orElseThrow();
        assertThat(child.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(child.parentExecutionId()).isEqualTo(parent.id());
        assertThat(child.rootExecutionId()).isEqualTo(parent.id());
        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.input()).isEqualTo("이것을 조사해라");
            assertThat(command.profileName()).isEqualTo("child-dad");
            // 자식은 부모의 session 을 잇지 않는다. 중간 산출물이 대화 session 에 쌓이면 안 된다.
            assertThat(command.sessionId()).isNull();
        });
    }

    @Test
    void 자식이_다시_자식을_부르면_ORCHESTRATION_DEPTH_EXCEEDED다() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        Conversation conversation = conversationOf(dad, "child-dad");
        AgentExecution parent = parentOf(dad, conversation, "child-dad");
        stub().willReturn(completed("run-child", "조사 결과"));
        ChildResult child = children.run(dad, conversation, parent, "child-dad", "이것을 조사해라");
        AgentExecution asParent = executions.findById(child.executionId()).orElseThrow();

        assertThatThrownBy(() -> children.run(dad, conversation, asParent, "child-dad", "더 파고들어라"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.ORCHESTRATION_DEPTH_EXCEEDED);
        assertThat(executions.count()).isEqualTo(2);
    }

    @Test
    void 경계1_자식의_userId가_부모와_같다() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        Conversation conversation = conversationOf(dad, "child-dad");
        AgentExecution parent = parentOf(dad, conversation, "child-dad");
        stub().willReturn(completed("run-child", "답"));

        ChildResult result = children.run(dad, conversation, parent, "child-dad", "지시");

        assertThat(executions.findById(result.executionId()).orElseThrow().userId())
                .isEqualTo(parent.userId())
                .isEqualTo(dad.id());
    }

    @Test
    void 경계2_요청자가_쓸_수_없는_에이전트는_AGENT_NOT_FOUND다() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        member("child-mom@example.com", "child-mom");
        Conversation conversation = conversationOf(dad, "child-dad");
        AgentExecution parent = parentOf(dad, conversation, "child-dad");

        assertThatThrownBy(() -> children.run(dad, conversation, parent, "child-mom", "지시"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_NOT_FOUND);
        assertThat(stub().received()).isEmpty();
    }

    @Test
    void 경계3_자식의_instructions에_다른_구성원의_개인_Memory가_없다() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        CurrentUser mom = member("child-mom@example.com", "child-mom");
        memories.create(dad, MemoryScope.USER, "아빠", "아빠는 국수를 맵지 않게 먹는다", true);
        memories.create(mom, MemoryScope.USER, "엄마", "엄마는 고수를 먹지 않는다", true);
        Conversation conversation = conversationOf(dad, "child-dad");
        AgentExecution parent = parentOf(dad, conversation, "child-dad");
        stub().willReturn(completed("run-child", "답"));

        children.run(dad, conversation, parent, "child-dad", "지시");

        String instructions = stub().received().getFirst().instructions();
        assertThat(instructions).contains("아빠는 국수를 맵지 않게 먹는다");
        assertThat(instructions).doesNotContain("엄마는 고수를 먹지 않는다");
    }

    @Test
    void 자식이_실패해도_실행_줄이_FAILED로_남고_부모의_흐름을_끊지_않는다() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        Conversation conversation = conversationOf(dad, "child-dad");
        AgentExecution parent = parentOf(dad, conversation, "child-dad");
        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        ChildResult result = children.run(dad, conversation, parent, "child-dad", "지시");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        AgentExecution child = executions.findById(result.executionId()).orElseThrow();
        assertThat(child.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(child.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        assertThat(child.parentExecutionId()).isEqualTo(parent.id());
        assertThat(executions.findById(parent.id()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void 자식의_답은_대화_이력에_들어가지_않고_실행_사건으로만_남는다() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        Conversation conversation = conversationOf(dad, "child-dad");
        AgentExecution parent = parentOf(dad, conversation, "child-dad");
        stub().willReturn(completed("run-child", "중간 산출물"));

        ChildResult result = children.run(dad, conversation, parent, "child-dad", "지시");

        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
        assertThat(executionEvents
                        .findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(result.executionId()))
                        .stream()
                        .map(event -> event.eventType())
                        .toList())
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.RUN_COMPLETED);
    }
}
