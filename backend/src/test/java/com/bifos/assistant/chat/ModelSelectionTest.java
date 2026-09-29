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
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
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
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 실행이 대화가 고른 모델과 effort 로 Hermes 를 부르고, 실제로 돈 모델을 적고, 막혀도 넘기지 않는 것을 본다.
 *
 * <p>한 provider 안에서 계정을 돌려 쓰는 것은 Hermes 가 이미 하므로 검사하지 않는다.
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

    /** 금액이 비는 것이 가격표가 없어서가 아니라는 것을 보이려고 표본 가격표를 가리킨다. */
    @DynamicPropertySource
    static void pointAtTheSampleCatalog(DynamicPropertyRegistry registry) {
        registry.add("assistant.pricing.catalog-path", () -> sampleCatalog().toString());
    }

    private static Path sampleCatalog() {
        try {
            return Path.of(ModelSelectionTest.class.getResource("/pricing/models-dev-sample.json").toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 가격표에 있는 모델로 돌았다면 금액이 나오는 사용량이다. */
    private static final TokenUsage PRICED_USAGE = new TokenUsage(1_000L, 0L, 500L, 1_500L);

    @Autowired ChatService chat;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired ChatMessageRepository messages;
    @Autowired ConversationRepository conversations;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired HermesRunsClient hermes;

    @MockitoBean HermesRunEventStream eventStream;

    private CurrentUser user;

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
        agents.findByCode(AGENT_CODE).ifPresent(agents::delete);
        users.findByEmail("selection@example.com").ifPresent(users::delete);

        AppUser saved = users.save(AppUser.of("selection@example.com", "고름", 1L, UserRole.MEMBER));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
        agents.save(Agent.of(
                AGENT_CODE,
                "고름",
                AGENT_CODE,
                "http://agent-runtime.test/p/" + AGENT_CODE,
                CostMode.API,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                saved.id()));
    }

    @Test
    void 고르지_않은_대화는_provider_모델_effort_를_모두_빼고_보낸다() {
        stub().willReturn(succeeded("run-1", "sess-1"));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.provider()).as("provider").isNull();
            assertThat(command.model()).as("model").isNull();
            assertThat(command.reasoningEffort()).as("reasoning effort").isNull();
        });
        assertThat(executions.findById(turn.executionId()).orElseThrow().reasoningEffort()).isNull();
    }

    @Test
    void 모델과_effort_를_고르면_요청에_셋이_실리고_실행_줄에_effort_가_남는다() {
        Long conversationId = conversationChoosing(new ModelChoice("anthropic", "example-model-large", "high"));
        stub().willReturn(succeeded("run-1", "sess-1"));

        ChatTurn turn = chat.send(user, conversationId, "안녕", null);

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.provider()).isEqualTo("anthropic");
            assertThat(command.model()).isEqualTo("example-model-large");
            assertThat(command.reasoningEffort()).isEqualTo("high");
        });
        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.reasoningEffort()).isEqualTo("high");
        assertThat(execution.provider()).isEqualTo("anthropic");
        assertThat(execution.model()).isEqualTo("example-model-large");
    }

    /** 실행 조회의 {@code model} 이 아니라 세션 조회의 값이 기록돼야 한다. */
    @Test
    void 세션이_고른_것과_다른_모델을_답하면_그_값을_적는다() {
        Long conversationId = conversationChoosing(new ModelChoice("anthropic", "example-model-large", "low"));
        stub().willReturn(succeeded("run-1", "sess-1"));
        stub().willReportSessionRuntime(
                new SessionRuntime("example-provider/example-model-c", "nvidia"));

        ChatTurn turn = chat.send(user, conversationId, "안녕", null);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.model()).isEqualTo("example-provider/example-model-c");
        assertThat(execution.provider()).isEqualTo("nvidia");
        assertThat(execution.reasoningEffort()).isEqualTo("low");
        assertThat(stub().sessionLookups()).containsExactly("sess-1");
    }

    @Test
    void 기본값으로_보냈고_세션이_답하지_못하면_provider_모델_금액이_비어_있다() {
        stub().willReturn(succeeded("run-1", "sess-1"));
        stub().willReportSessionRuntime(null);

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.provider()).as("provider").isNull();
        assertThat(execution.model()).as("model").isNull();
        assertThat(execution.estimatedCostMicros()).as("estimated cost").isNull();
    }

    @Test
    void 세션이_provider_를_주지_않아도_실행의_runtime_으로_provider_와_모델을_적는다() {
        // v0.21.5 의 세션 조회는 model 만 주고 provider 를 주지 않는다. 실행 조회의 runtime 이 실제로 돈 값을 준다.
        stub().willReturn(new HermesRunResult("run-1", "sess-1", "completed", "네", "dad", null, null, PRICED_USAGE,
                new SessionRuntime("example-model-large", "anthropic")));
        stub().willReportSessionRuntime(new SessionRuntime("example-model-large", null));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.provider()).isEqualTo("anthropic");
        assertThat(execution.model()).isEqualTo("example-model-large");
        assertThat(execution.estimatedCostMicros()).as("provider 와 모델을 알면 금액을 적는다").isNotNull();
    }

    @Test
    void 기본값으로_보냈어도_세션이_답하면_그_모델로_금액을_적는다() {
        stub().willReturn(succeeded("run-1", "sess-1"));
        stub().willReportSessionRuntime(new SessionRuntime("example-model-large", "anthropic"));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.provider()).isEqualTo("anthropic");
        assertThat(execution.model()).isEqualTo("example-model-large");
        assertThat(execution.estimatedCostMicros()).as("estimated cost").isNotNull();
    }

    @Test
    void provider_가_막히면_넘기지_않고_PROVIDER_BLOCKED_로_실패한다() {
        Long conversationId = conversationChoosing(new ModelChoice("openai-codex", "example-model", null));
        stub().willReturnInOrder(blocked("run-1", "sess-1"), succeeded("run-2", "sess-1"));

        assertThatThrownBy(() -> chat.send(user, conversationId, "안녕", null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PROVIDER_BLOCKED);

        assertThat(stub().received()).as("Hermes 를 부른 횟수").hasSize(1);
        List<AgentExecution> recorded =
                executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 10));
        assertThat(recorded).singleElement().satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(failed.errorCode()).isEqualTo("PROVIDER_BLOCKED");
            assertThat(failed.provider()).isEqualTo("openai-codex");
        });
        List<ExecutionEvent> events = executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(
                List.of(recorded.getFirst().id()));
        assertThat(events).extracting(ExecutionEvent::eventType)
                .doesNotContain(ExecutionEventType.PROVIDER_SWITCHED)
                .contains(ExecutionEventType.RUN_FAILED);
    }

    @Test
    void 스트리밍에서_막혀도_넘김_알림과_조각_지우기를_보내지_않는다() {
        Long conversationId = conversationChoosing(new ModelChoice("openai-codex", "example-model", null));
        stub().willReturn(blocked("run-1", "sess-1"));

        List<ChatEvent> relayed = new ArrayList<>();
        assertThatThrownBy(() -> chat.stream(user, conversationId, "안녕", null, relayed::add))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PROVIDER_BLOCKED);

        assertThat(relayed).extracting(ChatEvent::type)
                .contains("started")
                .doesNotContain("switched", "reset");
    }

    @Test
    void 다른_실패는_넘기지_않고_한_번만_실패한다() {
        stub().willReturn(
                new HermesRunResult(
                        "run-1", "sess-1", "failed", null, "example-model", "openai-codex",
                        "HTTP 404: 404 page not found", TokenUsage.empty()));

        assertThatThrownBy(() -> chat.send(user, null, "안녕", AGENT_CODE))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_RUN_FAILED);

        assertThat(stub().received()).hasSize(1);
    }

    @Test
    void 선택을_바꾼_뒤_이어_보내면_바꾼_값이_실린다() {
        Long conversationId = conversationChoosing(new ModelChoice("anthropic", "example-model-large", "high"));
        stub().willReturn(succeeded("run-1", "sess-1"));
        chat.send(user, conversationId, "안녕", null);

        chat.chooseModel(user, conversationId, new ModelChoice("openai-codex", "example-model", "low"));
        chat.send(user, conversationId, "하나 더", null);

        assertThat(stub().received()).hasSize(2);
        assertThat(stub().received().getLast()).satisfies(command -> {
            assertThat(command.provider()).isEqualTo("openai-codex");
            assertThat(command.model()).isEqualTo("example-model");
            assertThat(command.reasoningEffort()).isEqualTo("low");
        });
    }

    /** 빈 대화를 만들어 모델을 고른다. 화면이 첫 메시지 전에 고를 때 밟는 길이다. */
    private Long conversationChoosing(ModelChoice choice) {
        Conversation conversation = chat.startEmpty(user, AGENT_CODE);
        chat.chooseModel(user, conversation.id(), choice);
        return conversation.id();
    }

    private static HermesRunResult succeeded(String runId, String sessionId) {
        return HermesRunResult.of(runId, sessionId, "completed", "네", "example-model", null, PRICED_USAGE);
    }

    private static HermesRunResult blocked(String runId, String sessionId) {
        return new HermesRunResult(
                runId, sessionId, "failed", null, "example-model", null, BLOCKED_ERROR, TokenUsage.empty());
    }
}
