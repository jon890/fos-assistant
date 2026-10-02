package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.ModelHidden;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ModelHiddenRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.ReasoningCapability;
import com.bifos.assistant.hermes.dto.ReasoningCapability.Support;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.domain.type.ReasoningEffortSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    private static final String BLOCKED_ERROR = HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX
            + " No Codex credentials stored. Run `hermes auth` to authenticate.";

    /** 금액이 비는 것이 가격표가 없어서가 아니라는 것을 보이려고 표본 가격표를 가리킨다. */
    @DynamicPropertySource
    static void pointAtTheSampleCatalog(DynamicPropertyRegistry registry) {
        registry.add("assistant.pricing.catalog-path", () -> sampleCatalog().toString());
    }

    private static Path sampleCatalog() {
        try {
            return Path.of(ModelSelectionTest.class
                    .getResource("/pricing/models-dev-sample.json")
                    .toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** reasoning 끄기를 받는다고 Hermes 가 밝힌 모델이다. */
    private static final String CAN_DISABLE_MODEL = "example-model-off";

    /** reasoning 끄기 지원을 Hermes 가 밝히지 않은 모델이다. */
    private static final String DISABLE_UNKNOWN_MODEL = "example-model-unsaid";

    /** reasoning 끄기를 받지 않는다고 Hermes 가 밝힌 모델이다. */
    private static final String CANNOT_DISABLE_MODEL = "example-model-on";

    /**
     * Hermes 가 늘 답하는 목록이다. profile 의 기본 모델이 {@code example-provider} 의 {@code example-model} 이다.
     *
     * <p>목록은 profile 마다 10분 동안 메모리에 남으므로 끄기 지원이 {@code SUPPORTED}, {@code UNKNOWN},
     * {@code UNSUPPORTED} 인 모델을 한 목록에 모두 둔다. {@code example-model} 은 항목이 없어 {@code UNKNOWN} 으로 읽힌다.
     */
    private static final HermesModelCatalog PROFILE_CATALOG = new HermesModelCatalog(
            "example-provider",
            "example-model",
            List.of(new HermesModelCatalog.Provider(
                    "example-provider",
                    "example-provider",
                    List.of("example-model", CAN_DISABLE_MODEL, DISABLE_UNKNOWN_MODEL, CANNOT_DISABLE_MODEL),
                    Map.of(
                            CAN_DISABLE_MODEL,
                            new ReasoningCapability(Support.SUPPORTED, Support.SUPPORTED),
                            DISABLE_UNKNOWN_MODEL,
                            new ReasoningCapability(Support.SUPPORTED, Support.UNKNOWN),
                            CANNOT_DISABLE_MODEL,
                            new ReasoningCapability(Support.SUPPORTED, Support.UNSUPPORTED)))));

    /** 가격표에 있는 모델로 돌았다면 금액이 나오는 사용량이다. */
    private static final TokenUsage PRICED_USAGE = new TokenUsage(1_000L, 0L, 500L, 1_500L);

    @Autowired
    ChatService chat;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    ModelHiddenRepository hiddenModels;

    @MockitoBean
    HermesRunEventStream eventStream;

    /**
     * profile 의 기본 모델을 읽는 길이다. 숨김이 있는 그룹의 기본값 실행만 이 목록을 읽는다.
     *
     * <p>읽은 목록은 Spring context 가 사는 동안 메모리에 남는다. 그래서 검사마다 다른 목록을 주지 않고 늘 같은
     * 목록을 답하게 한다. 어느 검사가 먼저 읽어도 결과가 같다.
     */
    @MockitoBean
    HermesModelClient modelClient;

    private CurrentUser user;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        stub().reset();
        when(modelClient.readCatalog(anyString(), anyString())).thenReturn(PROFILE_CATALOG);
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.findByCode(AGENT_CODE).ifPresent(agents::delete);
        users.findByEmail("selection@example.com").ifPresent(users::delete);

        AppUser saved = users.save(AppUser.of("selection@example.com", "고름", 1L, UserRole.MEMBER, Instant.now()));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
        agents.save(Agent.of(
                AGENT_CODE,
                "고름",
                AGENT_CODE,
                "http://agent-runtime.test/p/" + AGENT_CODE,
                CostMode.API,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                saved.id(),
                Instant.now()));
    }

    @Test
    @DisplayName("고르지 않은 대화는 provider 모델 effort 를 모두 빼고 보낸다")
    void unchosenConversationSendsWithoutProviderModelAndEffort() {
        stub().willReturn(succeeded("run-1", "sess-1"));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.provider()).as("provider").isNull();
            assertThat(command.model()).as("model").isNull();
            assertThat(command.reasoningEffort()).as("reasoning effort").isNull();
        });
        assertThat(executions.findById(turn.executionId()).orElseThrow().reasoningEffort())
                .isNull();
    }

    @Test
    @DisplayName("모델과 effort 를 고르면 요청에 셋이 실리고 실행 줄에 effort 가 남는다")
    void choosingModelAndEffortCarriesAllThreeAndRecordsEffortInRunRow() {
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
    @DisplayName("세션이 고른 것과 다른 모델을 답하면 그 값을 적는다")
    void recordsModelWhenSessionAnswersOtherThanChosen() {
        Long conversationId = conversationChoosing(new ModelChoice("anthropic", "example-model-large", "low"));
        stub().willReturn(succeeded("run-1", "sess-1"));
        stub().willReportSessionRuntime(new SessionRuntime("example-provider/example-model-c", "nvidia"));

        ChatTurn turn = chat.send(user, conversationId, "안녕", null);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.model()).isEqualTo("example-provider/example-model-c");
        assertThat(execution.provider()).isEqualTo("nvidia");
        assertThat(execution.reasoningEffort()).isEqualTo("low");
        assertThat(stub().sessionLookups()).containsExactly("sess-1");
    }

    @Test
    @DisplayName("기본값으로 보냈고 세션이 답하지 못하면 provider 모델 금액이 비어 있다")
    void leavesProviderModelAndAmountEmptyWhenDefaultSentAndSessionSilent() {
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
    @DisplayName("세션이 provider 를 주지 않아도 실행의 runtime 으로 provider 와 모델을 적는다")
    void recordsProviderAndModelFromRuntimeEvenIfSessionGivesNoProvider() {
        // v0.21.5 의 세션 조회는 model 만 주고 provider 를 주지 않는다. 실행 조회의 runtime 이 실제로 돈 값을 준다.
        stub().willReturn(new HermesRunResult(
                "run-1",
                "sess-1",
                "completed",
                "네",
                "dad",
                null,
                null,
                PRICED_USAGE,
                new SessionRuntime("example-model-large", "anthropic")));
        stub().willReportSessionRuntime(new SessionRuntime("example-model-large", null));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.provider()).isEqualTo("anthropic");
        assertThat(execution.model()).isEqualTo("example-model-large");
        assertThat(execution.estimatedCostMicros())
                .as("provider 와 모델을 알면 금액을 적는다")
                .isNotNull();
    }

    @Test
    @DisplayName("기본값으로 보냈어도 세션이 답하면 그 모델로 금액을 적는다")
    void recordsAmountByModelWhenSessionAnswersEvenIfSentWithDefault() {
        stub().willReturn(succeeded("run-1", "sess-1"));
        stub().willReportSessionRuntime(new SessionRuntime("example-model-large", "anthropic"));

        ChatTurn turn = chat.send(user, null, "안녕", AGENT_CODE);

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.provider()).isEqualTo("anthropic");
        assertThat(execution.model()).isEqualTo("example-model-large");
        assertThat(execution.estimatedCostMicros()).as("estimated cost").isNotNull();
    }

    @Test
    @DisplayName("provider 가 막히면 넘기지 않고 PROVIDER BLOCKED 로 실패한다")
    void providerBlockedFailsAsProviderBlockedWithoutFallback() {
        Long conversationId = conversationChoosing(new ModelChoice("openai-codex", "example-model", null));
        stub().willReturnInOrder(blocked("run-1", "sess-1"), succeeded("run-2", "sess-1"));

        assertThatThrownBy(() -> chat.send(user, conversationId, "안녕", null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PROVIDER_BLOCKED);

        assertThat(stub().received()).as("Hermes 를 부른 횟수").hasSize(1);
        List<AgentExecution> recorded = executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 10));
        assertThat(recorded).singleElement().satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(failed.errorCode()).isEqualTo("PROVIDER_BLOCKED");
            assertThat(failed.provider()).isEqualTo("openai-codex");
        });
        List<ExecutionEvent> events = executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(
                List.of(recorded.getFirst().id()));
        assertThat(events)
                .extracting(ExecutionEvent::eventType)
                .doesNotContain(ExecutionEventType.PROVIDER_SWITCHED)
                .contains(ExecutionEventType.RUN_FAILED);
    }

    @Test
    @DisplayName("스트리밍에서 막혀도 넘김 알림과 조각 지우기를 보내지 않는다")
    void blockedInStreamingSendsNoFallbackNoticeNorChunkClear() {
        Long conversationId = conversationChoosing(new ModelChoice("openai-codex", "example-model", null));
        stub().willReturn(blocked("run-1", "sess-1"));

        List<ChatEvent> relayed = new ArrayList<>();
        assertThatThrownBy(() -> chat.stream(user, conversationId, "안녕", null, relayed::add))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PROVIDER_BLOCKED);

        assertThat(relayed).extracting(ChatEvent::type).contains("started").doesNotContain("switched", "reset");
    }

    @Test
    @DisplayName("다른 실패는 넘기지 않고 한 번만 실패한다")
    void otherFailuresFailOnceWithoutFallback() {
        stub().willReturn(new HermesRunResult(
                "run-1",
                "sess-1",
                "failed",
                null,
                "example-model",
                "openai-codex",
                "HTTP 404: 404 page not found",
                TokenUsage.empty()));

        assertThatThrownBy(() -> chat.send(user, null, "안녕", AGENT_CODE))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_RUN_FAILED);

        assertThat(stub().received()).hasSize(1);
    }

    @Test
    @DisplayName("선택을 바꾼 뒤 이어 보내면 바꾼 값이 실린다")
    void changedChoiceIsCarriedWhenContinuingAfterChange() {
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

    @Test
    @DisplayName("profile 의 기본 모델을 숨기면 모델을 싣지 않는 실행을 제출하지 않고 MODEL HIDDEN 으로 남기며 숨김을 비우면 다시 돈다")
    void rejectsDefaultRunWhileProfileDefaultModelIsHiddenAndRunsAgainAfterUnhiding() {
        Long conversationId = chat.startEmpty(user, AGENT_CODE).id();
        stub().willReturn(succeeded("run-1", "sess-1"));
        hiddenModels.save(ModelHidden.of(user.groupId(), "example-provider", "example-model"));
        try {
            assertThatThrownBy(() -> chat.send(user, conversationId, "안녕", null))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.MODEL_HIDDEN);

            assertThat(stub().received()).as("숨긴 동안 Hermes 에 제출한 수").isEmpty();
            assertThat(executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 10)))
                    .singleElement()
                    .satisfies(failed -> {
                        assertThat(failed.status()).isEqualTo(ExecutionStatus.FAILED);
                        assertThat(failed.errorCode()).isEqualTo(ErrorCode.MODEL_HIDDEN.name());
                    });
        } finally {
            // 이 그룹은 다른 검사도 쓴다. 숨김이 남으면 그 검사들의 기본값 실행이 막힌다.
            hiddenModels.deleteAll(hiddenModels.findByGroupIdOrderByProviderAscModelAsc(user.groupId()));
        }

        ChatTurn turn = chat.send(user, conversationId, "안녕", null);

        assertThat(executions.findById(turn.executionId()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.provider()).as("provider").isNull();
            assertThat(command.model()).as("model").isNull();
        });
    }

    @Test
    @DisplayName("요청 effort 는 none 을 받고 minimal 은 VALIDATION FAILED 로 거절한다")
    void acceptsNoneAndRejectsMinimalAsRequestEffort() {
        assertThat(ModelChoice.of("example-provider", CAN_DISABLE_MODEL, "none").reasoningEffort())
                .isEqualTo("none");
        assertThatThrownBy(() -> ModelChoice.of("example-provider", CAN_DISABLE_MODEL, "minimal"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("끄기 지원이 SUPPORTED 인 모델은 대화에 none 을 저장한다")
    void storesNoneForModelThatSupportsDisabling() {
        Long conversationId = chat.startEmpty(user, AGENT_CODE).id();

        Conversation saved =
                chat.chooseModel(user, conversationId, ModelChoice.of("example-provider", CAN_DISABLE_MODEL, "none"));

        assertThat(saved.modelChoice().model()).isEqualTo(CAN_DISABLE_MODEL);
        assertThat(saved.modelChoice().reasoningEffort()).isEqualTo("none");
    }

    @Test
    @DisplayName("끄기 지원이 UNKNOWN 이나 UNSUPPORTED 인 모델은 none 을 VALIDATION FAILED 로 거절하고 저장된 값을 남긴다")
    void rejectsNoneForModelWithUnknownOrUnsupportedDisablingAndKeepsStoredChoice() {
        Long conversationId = conversationChoosing(ModelChoice.of("example-provider", "example-model", "high"));

        for (String model : List.of(DISABLE_UNKNOWN_MODEL, CANNOT_DISABLE_MODEL)) {
            assertThatThrownBy(() ->
                            chat.chooseModel(user, conversationId, ModelChoice.of("example-provider", model, "none")))
                    .as("모델 %s", model)
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        ModelChoice stored =
                conversations.findById(conversationId).orElseThrow().modelChoice();
        assertThat(stored.model()).isEqualTo("example-model");
        assertThat(stored.reasoningEffort()).isEqualTo("high");
    }

    @Test
    @DisplayName("none 을 저장한 대화의 실행은 none 을 요청 값으로 적고 Hermes 가 다른 모델을 알려도 effort 를 바꾸지 않는다")
    void recordsNoneAsRequestedEffortEvenWhenHermesReportsOtherModel() {
        Long conversationId = conversationChoosing(ModelChoice.of("example-provider", CAN_DISABLE_MODEL, "none"));
        stub().willReturn(new HermesRunResult(
                "run-1",
                "sess-1",
                "completed",
                "네",
                "dad",
                null,
                null,
                PRICED_USAGE,
                new SessionRuntime("example-model-large", "anthropic")));
        stub().willReportSessionRuntime(new SessionRuntime("example-model-large", "anthropic"));

        ChatTurn turn = chat.send(user, conversationId, "안녕", null);

        assertThat(stub().received())
                .singleElement()
                .satisfies(command -> assertThat(command.reasoningEffort()).isEqualTo("none"));
        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.model()).as("실제로 돈 모델").isEqualTo("example-model-large");
        assertThat(execution.reasoningEffort()).as("보낸 effort").isEqualTo("none");
        assertThat(execution.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.REQUESTED);
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
