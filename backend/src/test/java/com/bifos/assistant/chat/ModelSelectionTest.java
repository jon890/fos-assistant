package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.ProviderStateRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 실행마다 모델을 정해 보내고, 실제로 돈 모델을 적고, 막히면 다음 순위로 넘기는 것을 본다.
 *
 * <p>여기서 다루는 것은 provider 를 가로지르는 넘김 하나다. 한 provider 안에서 계정을 돌려 쓰는 것은
 * Hermes 가 이미 하므로 검사하지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ModelSelectionTest.StubRuntime.class)
class ModelSelectionTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    private static final String AGENT_CODE = "selection";

    /** 그 provider 의 계정이 전부 막혔을 때 Hermes 가 돌려주는 글이다. 실측한 문장이다. */
    private static final String BLOCKED_ERROR =
            HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX
                    + " No Codex credentials stored. Run `hermes auth` to authenticate.";

    @Autowired ChatService chat;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired AgentModelSelector modelSelector;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired ProviderStateRepository providerStates;
    @Autowired ChatMessageRepository messages;
    @Autowired ConversationRepository conversations;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired HermesRunsClient hermes;

    @MockitoBean HermesRunEventStream eventStream;

    private CurrentUser user;
    private Agent agent;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void 준비한다() {
        stub().reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        providerStates.deleteAll();
        modelOptions.deleteAll();
        agents.findByCode(AGENT_CODE).ifPresent(agents::delete);
        users.findByEmail("selection@example.com").ifPresent(users::delete);

        AppUser saved = users.save(AppUser.of("selection@example.com", "고름", 1L, UserRole.MEMBER));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.familyId(), saved.role());
        agent = agents.save(Agent.of(
                AGENT_CODE,
                "고름",
                AGENT_CODE,
                "http://agent-runtime.test/p/" + AGENT_CODE,
                "openai-codex",
                "gpt-5.6-sol",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                saved.id()));
        modelSelector.replace(
                agent,
                List.of(
                        new ModelOption("openai-codex", "gpt-5.6-sol"),
                        new ModelOption("nvidia", "nvidia/nemotron-3-super-120b-a12b")));
    }

    @Test
    void 요청에_1순위의_provider_와_모델을_함께_싣는다() {
        stub().willReturn(succeeded("run-1", "sess-1"));

        chat.send(user, null, "안녕", AGENT_CODE);

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.provider()).isEqualTo("openai-codex");
            assertThat(command.model()).isEqualTo("gpt-5.6-sol");
        });
    }

    /** 이 검사가 고치는 것이다. 실행 조회의 {@code model} 이 아니라 세션 조회의 값이 기록돼야 한다. */
    @Test
    void 세션_조회가_요청과_다른_모델을_주면_그_값을_기록한다() {
        stub().willReturn(succeeded("run-1", "sess-1"));
        stub().willReportSessionRuntime(
                new SessionRuntime("nvidia/nemotron-3.5-lightning-30b-a3b", "nvidia"));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.model()).isEqualTo("nvidia/nemotron-3.5-lightning-30b-a3b");
        assertThat(execution.provider()).isEqualTo("nvidia");
        assertThat(stub().sessionLookups()).containsExactly("sess-1");
    }

    @Test
    void 세션_조회가_실패하면_요청에_보낸_값을_적고_실행은_성공으로_남는다() {
        stub().willReturn(succeeded("run-1", "sess-1"));
        stub().willReportSessionRuntime(null);

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.provider()).isEqualTo("openai-codex");
        assertThat(execution.model()).isEqualTo("gpt-5.6-sol");
    }

    @Test
    void 막히면_그_턴_안에서_2순위로_다시_보내고_답이_온다() {
        stub().willReturnInOrder(blocked("run-1", "sess-1"), succeeded("run-2", "sess-1"));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        assertThat(turn.assistantText()).isEqualTo("네");
        assertThat(stub().received())
                .extracting(HermesRunCommand::provider)
                .containsExactly("openai-codex", "nvidia");
    }

    @Test
    void 막힌_provider_를_기억하고_실패한_실행도_남긴다() {
        stub().willReturnInOrder(blocked("run-1", "sess-1"), succeeded("run-2", "sess-1"));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        assertThat(providerStates.findById("openai-codex")).isPresent();
        List<AgentExecution> recorded =
                executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 10));
        assertThat(recorded).hasSize(2);
        AgentExecution failed = recorded.get(1);
        assertThat(failed.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("PROVIDER_BLOCKED");
        assertThat(failed.provider()).isEqualTo("openai-codex");
        assertThat(recorded.getFirst().id()).isEqualTo(turn.executionId());
        assertThat(recorded.getFirst().retryOfExecutionId()).isEqualTo(failed.id());
    }

    @Test
    void 넘어간_실행에_PROVIDER_SWITCHED_사건이_남는다() {
        stub().willReturnInOrder(blocked("run-1", "sess-1"), succeeded("run-2", "sess-1"));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        List<ExecutionEvent> events =
                executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(
                        List.of(turn.executionId()));
        assertThat(events.getFirst().eventType()).isEqualTo(ExecutionEventType.PROVIDER_SWITCHED);
        assertThat(events.getFirst().detail())
                .isEqualTo("nvidia/nvidia/nemotron-3-super-120b-a12b");
    }

    @Test
    void 막힌_동안_다음_턴은_1순위를_건너뛴다() {
        stub().willReturnInOrder(blocked("run-1", "sess-1"), succeeded("run-2", "sess-1"));
        chat.send(user, null, "안녕", AGENT_CODE);

        stub().reset();
        stub().willReturn(succeeded("run-3", "sess-2"));
        chat.send(user, null, "하나 더", AGENT_CODE);

        assertThat(stub().received())
                .extracting(HermesRunCommand::provider)
                .containsExactly("nvidia");
    }

    /**
     * 목록은 turn 이 시작할 때 한 번 정해진다. 그래서 같은 provider 의 다음 모델이 그 turn 안에서 성공할
     * 수 있고, 그때는 식는 시간이 남아 있어도 막히지 않은 것으로 본다.
     */
    @Test
    void 성공하면_그_provider_의_막힘이_풀린다() {
        modelSelector.replace(
                agent,
                List.of(
                        new ModelOption("openai-codex", "gpt-5.6-luna"),
                        new ModelOption("openai-codex", "gpt-5.6-sol")));
        stub().willReturnInOrder(blocked("run-1", "sess-1"), succeeded("run-2", "sess-1"));

        chat.send(user, null, "안녕", AGENT_CODE);

        assertThat(providerStates.findById("openai-codex").orElseThrow().blockedAt(Instant.now()))
                .isFalse();
    }

    @Test
    void 다른_실패는_넘기지_않고_한_번만_실패한다() {
        stub().willReturn(
                new HermesRunResult(
                        "run-1", "sess-1", "failed", null, "gpt-5.6-sol", "openai-codex",
                        "HTTP 404: 404 page not found", TokenUsage.empty()));

        assertThatThrownBy(() -> chat.send(user, null, "안녕", AGENT_CODE))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_RUN_FAILED);

        assertThat(stub().received()).hasSize(1);
        assertThat(providerStates.count()).isZero();
    }

    @Test
    void 전부_막히면_Hermes_를_부르지_않고_NO_MODEL_AVAILABLE_로_실패한다() {
        stub().willReturnInOrder(blocked("run-1", "sess-1"), blocked("run-2", "sess-1"));
        assertThatThrownBy(() -> chat.send(user, null, "안녕", AGENT_CODE))
                .isInstanceOf(ApiException.class);

        stub().reset();
        assertThatThrownBy(() -> chat.send(user, null, "하나 더", AGENT_CODE))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.NO_MODEL_AVAILABLE);

        assertThat(stub().received()).isEmpty();
        assertThat(executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 10)).getFirst())
                .satisfies(execution -> {
                    assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(execution.errorCode()).isEqualTo("NO_MODEL_AVAILABLE");
                });
    }

    /**
     * 실패한 시도의 조각이 화면에 남으면 읽는 사람이 그것을 답으로 읽는다.
     *
     * <p>Hermes 는 계정이 막혔을 때 조각을 한 개도 보내지 않지만, 보내더라도 화면이 그것을 지우도록
     * {@code reset} 을 먼저 내보낸다.
     */
    @Test
    void 스트리밍에서_실패한_시도의_조각은_화면에_남지_않는다() {
        stub().willReturnInOrder(blocked("run-1", "sess-1"), succeeded("run-2", "sess-1"));
        hermesStreams(new RunEvent("message.delta", "버릴 조각", null, null, null, null));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(user, null, "안녕", AGENT_CODE, relayed::add);

        List<String> types = relayed.stream().map(ChatEvent::type).toList();
        assertThat(types).contains("reset", "switched");
        assertThat(types.indexOf("reset")).isLessThan(types.indexOf("switched"));
        assertThat(messages.findByConversationIdOrderByIdAsc(relayed.getLast().conversationId()))
                .last()
                .satisfies(message -> assertThat(message.content()).isEqualTo("네"));
    }

    private void hermesStreams(RunEvent... events) {
        doAnswer(invocation -> {
            Consumer<RunEvent> onEvent = invocation.getArgument(3);
            for (RunEvent event : events) {
                onEvent.accept(event);
            }
            return null;
        })
                .when(eventStream)
                .open(any(), any(), any(), any());
    }

    private static HermesRunResult succeeded(String runId, String sessionId) {
        return HermesRunResult.of(runId, sessionId, "completed", "네", "gpt-5.6-sol", null, TokenUsage.empty());
    }

    private static HermesRunResult blocked(String runId, String sessionId) {
        return new HermesRunResult(
                runId, sessionId, "failed", null, "gpt-5.6-sol", null, BLOCKED_ERROR, TokenUsage.empty());
    }
}
