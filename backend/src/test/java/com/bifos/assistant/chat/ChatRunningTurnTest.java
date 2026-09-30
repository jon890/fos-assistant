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
import com.bifos.assistant.chat.application.RunningTurn;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.ResearchAndBuildFlow;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 같은 대화를 연 다른 창이 도는 turn 을 알아보는 조회를 본다.
 *
 * <p>도는지는 실행 줄의 상태가 아니라 메모리 표시로 판정해야 한다. 흐름은 뿌리 줄이 끝난 뒤에도 자식이
 * 돌기 때문이다. 가짜 Hermes 가 결과를 돌려주기 전에 조회해 turn 이 도는 순간을 붙잡는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ChatRunningTurnTest {

    /** 흐름의 Chief 에게 준 지시에만 들어 있는 말이다. 대역이 이것으로 단계를 가려낸다. */
    private static final String CHIEF_MARK = "조사할 것과 만들 것을 나눈다";

    private static final String SPLIT_JSON = "{\"research\":\"전기차 보조금\",\"build\":\"비교 표\"}";

    @Autowired ChatService chat;
    @Autowired TurnCancellation turns;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired MemoryRepository memories;
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
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
    }

    private CurrentUser member(String name) {
        return member(name, null);
    }

    private CurrentUser member(String name, String flow) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, UserRole.MEMBER));
        Agent agent = Agent.of(
                name, name, name, "http://agent-runtime.test/p/" + name, CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, user.id());
        if (flow != null) agent.assignFlow(flow);
        agents.save(agent);
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private AgentExecution latestExecution(CurrentUser user) {
        return executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 1)).getFirst();
    }

    private static HermesRunResult completed(String runId, String output) {
        return HermesRunResult.of(runId, "session", "completed", output, "model", "provider", TokenUsage.empty());
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("도는 turn이 없는 내 대화는 돌지 않는다고 답한다")
    void ownConversationWithoutRunningTurnReportsNotRunning() {
        CurrentUser dad = member("dad");
        Conversation conversation = chat.startEmpty(dad, "dad");

        assertThat(chat.running(dad, conversation.id())).isEqualTo(new RunningTurn(false, null, null));
    }

    @Test
    @DisplayName("도는 turn이 있으면 뿌리 실행 번호와 시작 시각을 답하고 끝나면 돌지 않는다고 답한다")
    void runningTurnReportsRootRunIdAndStartTimeThenNotRunningAfterEnd() {
        CurrentUser dad = member("dad");
        stub().willReturn(completed("run-held", "답"));
        AtomicReference<RunningTurn> whileRunning = new AtomicReference<>();
        AtomicReference<AgentExecution> heldRow = new AtomicReference<>();
        stub().beforeAwait(() -> {
            AgentExecution row = latestExecution(dad);
            heldRow.set(row);
            whileRunning.set(chat.running(dad, row.conversationId()));
        });

        ChatTurn turn = chat.send(dad, null, "질문", "dad");

        assertThat(whileRunning.get())
                .isEqualTo(new RunningTurn(true, heldRow.get().id(), heldRow.get().startedAt()));
        assertThat(whileRunning.get().executionId()).isEqualTo(turn.executionId());
        assertThat(whileRunning.get().startedAt()).isNotNull();
        assertThat(chat.running(dad, turn.conversationId())).isEqualTo(new RunningTurn(false, null, null));
    }

    @Test
    @DisplayName("표시는 있는데 실행 번호가 붙기 전이면 돈다고만 답한다")
    void reportsOnlyRunningWhenMarkExistsBeforeRunIdIsAttached() {
        CurrentUser dad = member("dad");
        Conversation conversation = chat.startEmpty(dad, "dad");
        TurnCancellation.TurnHandle handle = turns.open(dad.id(), conversation.id());
        try {
            assertThat(chat.running(dad, conversation.id())).isEqualTo(new RunningTurn(true, null, null));
        } finally {
            turns.close(handle);
        }
    }

    @Test
    @DisplayName("흐름에서 Chief가 끝나 뿌리 줄이 SUCCEEDED여도 자식이 도는 동안은 뿌리 번호로 돈다고 답한다")
    void reportsRunningWithRootIdWhileChildRunsEvenIfRootRowSucceeded() {
        CurrentUser dad = member("flow-dad", ResearchAndBuildFlow.NAME);
        AtomicReference<RunningTurn> whileChildRuns = new AtomicReference<>();
        AtomicReference<ExecutionStatus> rootStatus = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        stub().willAnswer(command -> {
            String input = command.input();
            if (input.contains(CHIEF_MARK)) return completed("run-chief", SPLIT_JSON);
            if (input.contains("조사해")) {
                // 자식 단계는 다른 스레드에서 돈다. 여기서 난 실패는 따로 담아 본 스레드에서 단언한다.
                try {
                    AgentExecution child = latestExecution(dad);
                    RunningTurn running = chat.running(dad, child.conversationId());
                    whileChildRuns.set(running);
                    rootStatus.set(executions.findById(running.executionId()).orElseThrow().status());
                } catch (Throwable ex) {
                    failure.set(ex);
                }
                return completed("run-researcher", "조사한 것");
            }
            if (input.contains("만든다")) return completed("run-engineer", "만든 것");
            return completed("run-synthesizer", "합친 답");
        });

        ChatTurn turn = chat.send(dad, null, "보조금 비교해 줘", "flow-dad");

        assertThat(failure.get()).isNull();
        assertThat(rootStatus.get()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(whileChildRuns.get().running()).isTrue();
        assertThat(whileChildRuns.get().executionId()).isEqualTo(turn.executionId());
        assertThat(chat.running(dad, turn.conversationId()).running()).isFalse();
    }

    @Test
    @DisplayName("막히면 넘기지 않고 실패하고 turn이 끝난다")
    void blockedFailsWithoutFallbackAndEndsTurn() {
        CurrentUser dad = member("dad");
        stub().willReturnInOrder(
                new HermesRunResult("run-blocked", "session", "failed", "", null, null,
                        HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " no account", TokenUsage.empty()),
                completed("run-next", "답"));
        AtomicReference<RunningTurn> whileRunning = new AtomicReference<>();
        stub().beforeAwait(() -> whileRunning.set(chat.running(dad, latestExecution(dad).conversationId())));

        List<ChatEvent> relayed = new ArrayList<>();
        assertThatThrownBy(() -> chat.stream(dad, null, "막힌 모델로 보내 줘", "dad", relayed::add))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PROVIDER_BLOCKED);

        List<Long> started = relayed.stream()
                .filter(event -> event.type().equals("started")).map(ChatEvent::executionId).toList();
        assertThat(started).as("started 사건의 실행 번호").hasSize(1);
        assertThat(stub().received()).as("Hermes 를 부른 횟수").hasSize(1);
        assertThat(whileRunning.get().executionId()).isEqualTo(started.getFirst());
        AgentExecution failed = latestExecution(dad);
        assertThat(failed.id()).isEqualTo(started.getFirst());
        assertThat(failed.errorCode()).isEqualTo("PROVIDER_BLOCKED");
        assertThat(chat.running(dad, failed.conversationId()).running()).isFalse();
    }

    @Test
    @DisplayName("남의 대화는 도는 turn이 있어도 찾을 수 없다고 답한다")
    void othersConversationReportsNotFoundEvenWithRunningTurn() {
        CurrentUser dad = member("dad");
        CurrentUser mom = member("mom");
        stub().willReturn(completed("run-dad", "답"));
        AtomicReference<Long> dadConversation = new AtomicReference<>();
        AtomicReference<Throwable> momSaw = new AtomicReference<>();
        stub().beforeAwait(() -> {
            dadConversation.set(latestExecution(dad).conversationId());
            try {
                chat.running(mom, dadConversation.get());
            } catch (Throwable ex) {
                momSaw.set(ex);
            }
        });

        chat.send(dad, null, "질문", "dad");

        assertThat(momSaw.get()).isInstanceOf(ApiException.class);
        assertThat(((ApiException) momSaw.get()).code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
        assertNotFound(() -> chat.running(mom, dadConversation.get()));
    }

    @Test
    @DisplayName("지운 대화와 없는 대화는 찾을 수 없다고 답한다")
    void deletedAndMissingConversationsReportNotFound() {
        CurrentUser dad = member("dad");
        Conversation gone = chat.startEmpty(dad, "dad");
        chat.delete(dad, gone.id());

        assertNotFound(() -> chat.running(dad, gone.id()));
        assertNotFound(() -> chat.running(dad, Long.MAX_VALUE));
    }
}
