package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.AskFormat;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.ChildExecutionRunner;
import com.bifos.assistant.orchestration.domain.ChildResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 자식 실행 하나가 부모의 경계를 그대로 물려받는 것을 고정한다. */
@BackendIntegrationTest
class ChildExecutionRunnerTest {

    @Autowired
    ChildExecutionRunner children;

    @Autowired
    ExecutionRecorder recorder;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository memoryRepository;

    @Autowired
    HermesRunsClient hermes;

    /** 이 검사가 쓰는 에이전트와 사용자다. 다른 검사 클래스와 겹치지 않는 이름으로 둔다. */
    private static final List<String> MY_AGENTS = List.of("child-dad", "child-mom");

    private static final List<String> MY_EMAILS = List.of("child-dad@example.com", "child-mom@example.com");

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
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, Instant.now()));
        if (agentCode != null) {
            agents.save(Agent.of(
                    agentCode,
                    agentCode,
                    agentCode,
                    "http://agent-runtime.test/p/" + agentCode,
                    CostMode.SUBSCRIPTION,
                    CredentialScope.SHARED_HOUSEHOLD,
                    AgentVisibility.PRIVATE,
                    user.id(),
                    Instant.now()));
        }
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Conversation conversationOf(CurrentUser user, String agentCode) {
        Agent agent = agents.findByCode(agentCode).orElseThrow();
        return conversations.save(Conversation.startedBy(user.id(), "제목", agent.id(), Instant.now()));
    }

    /** 부모 실행 하나를 루트로 만든다. */
    private AgentExecution parentOf(CurrentUser user, Conversation conversation, String agentCode) {
        return recorder.start(
                user,
                conversation.executionConversation(),
                agents.findByCode(agentCode).orElseThrow(),
                null,
                null,
                0L);
    }

    private static HermesRunResult completed(String runId, String output) {
        return HermesRunResult.of(
                runId,
                "sess-child",
                "completed",
                output,
                "example-model-large",
                "anthropic",
                new TokenUsage(30L, 10L, 5L, 35L));
    }

    @Test
    @DisplayName("자식 실행이 부모를 가리키며 SUCCEEDED로 남는다")
    void childRunPointsToParentAndLeavesSucceeded() {
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
            // 자식은 부모의 session 을 잇지 않고 새 fos-<uuid> 를 보낸다. 중간 산출물이 대화 session 에 쌓이면 안 된다.
            assertThat(command.sessionId()).startsWith("fos-");
        });
        String sentSession = stub().received().get(0).sessionId();
        assertThat(child.hermesSessionId()).as("자식 실행 줄의 session").isEqualTo(sentSession);
    }

    /**
     * 자식 에이전트가 달라도 그 대화에서 고른 모델과 effort 로 돈다.
     *
     * <p>사용자가 이 대화에서 고른 모델이 그 대화의 모든 실행에 적용된다는 규칙을 하나로 둔다.
     */
    @Test
    @DisplayName("모델을 고른 대화의 자식 실행은 그 선택으로 Hermes를 부른다")
    void childRunOfConversationWithChosenModelCallsHermesWithThatChoice() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        member("child-mom@example.com", "child-mom");
        Agent shared = agents.findByCode("child-mom").orElseThrow();
        shared.changeAccess(true, AgentVisibility.GROUP, null);
        agents.save(shared);
        Conversation started = conversationOf(dad, "child-dad");
        transaction.executeWithoutResult(status -> conversations.chooseModelIfActive(
                started.id(), dad.id(), "nvidia", "nemotron", "high", ModelSelectionMode.CUSTOM));
        Conversation conversation = conversations.findById(started.id()).orElseThrow();
        AgentExecution parent = parentOf(dad, conversation, "child-dad");
        stub().willReturn(completed("run-child", "조사 결과"));

        ChildResult result = children.run(dad, conversation, parent, "child-mom", "이것을 조사해라");

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.profileName()).isEqualTo("child-mom");
            assertThat(command.provider()).isEqualTo("nvidia");
            assertThat(command.model()).isEqualTo("nemotron");
            assertThat(command.reasoningEffort()).isEqualTo("high");
        });
        assertThat(executions.findById(result.executionId()).orElseThrow().reasoningEffort())
                .isEqualTo("high");
    }

    @Test
    @DisplayName("모델을 고른 대화에서 자식 실행이 막히면 PROVIDER BLOCKED로 남는다")
    void childRunLeavesProviderBlockedWhenBlockedWithChosenModel() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        Conversation started = conversationOf(dad, "child-dad");
        transaction.executeWithoutResult(status -> conversations.chooseModelIfActive(
                started.id(), dad.id(), "nvidia", "nemotron", null, ModelSelectionMode.CUSTOM));
        Conversation conversation = conversations.findById(started.id()).orElseThrow();
        AgentExecution parent = parentOf(dad, conversation, "child-dad");
        stub().willReturn(new HermesRunResult(
                "run-blocked",
                "sess-child",
                "failed",
                null,
                null,
                null,
                HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " no account",
                TokenUsage.empty()));

        ChildResult result = children.run(dad, conversation, parent, "child-dad", "이것을 조사해라");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROVIDER_BLOCKED");
        assertThat(stub().received()).as("Hermes 를 부른 횟수").hasSize(1);
        AgentExecution child = executions.findById(result.executionId()).orElseThrow();
        assertThat(child.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(child.errorCode()).isEqualTo("PROVIDER_BLOCKED");
        assertThat(child.provider()).isEqualTo("nvidia");
    }

    @Test
    @DisplayName("자식이 다시 자식을 부르면 ORCHESTRATION DEPTH EXCEEDED다")
    void childCallingChildIsOrchestrationDepthExceeded() {
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
    @DisplayName("경계1 자식의 userId가 부모와 같다")
    void boundary1ChildUserIdEqualsParents() {
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
    @DisplayName("경계2 요청자가 쓸 수 없는 에이전트는 AGENT NOT FOUND다")
    void boundary2AgentRequesterCannotUseIsAgentNotFound() {
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
    @DisplayName("경계3 자식의 instructions에 다른 사용자의 개인 Memory가 없다")
    void boundary3ChildInstructionsHaveNoOtherUsersPersonalMemory() {
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
        assertThat(instructions).contains("GFM", "| --- | --- |");
        assertThat(instructions).doesNotContain("엄마는 고수를 먹지 않는다");
        // 자식의 답은 사람이 아니라 흐름이 읽으므로 묻는 형식 안내를 붙이지 않는다.
        assertThat(instructions).doesNotContain(AskFormat.GUIDE);
    }

    @Test
    @DisplayName("자식이 실패해도 실행 줄이 FAILED로 남고 부모의 흐름을 끊지 않는다")
    void childFailureLeavesRunRowFailedWithoutBreakingParentFlow() {
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
        assertThat(executions.findById(parent.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("자식의 답은 대화 이력에 들어가지 않고 실행 사건으로만 남는다")
    void childAnswerStaysAsRunEventOnlyAndNotInConversationHistory() {
        CurrentUser dad = member("child-dad@example.com", "child-dad");
        Conversation conversation = conversationOf(dad, "child-dad");
        AgentExecution parent = parentOf(dad, conversation, "child-dad");
        stub().willReturn(completed("run-child", "중간 산출물"));

        ChildResult result = children.run(dad, conversation, parent, "child-dad", "지시");

        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
        assertThat(
                        executionEvents
                                .findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(result.executionId()))
                                .stream()
                                .map(event -> event.eventType())
                                .toList())
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.RUN_COMPLETED);
    }
}
