package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.application.StarterProperties;
import com.bifos.assistant.chat.application.StarterStatus;
import com.bifos.assistant.chat.application.StarterSuggestionService;
import com.bifos.assistant.chat.application.StarterSuggestions;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.CatalogPrice;
import com.bifos.assistant.usage.domain.ModelPrice;
import com.bifos.assistant.usage.domain.PriceCatalog;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.domain.type.ReasoningEffortSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * 추천 질문을 언제 만들고 무엇을 돌려주는지 본다.
 *
 * <p>서비스를 직접 만들어 시각과 실행기를 바꿔 끼운다. 시각을 옮겨 오래됨과 재시도 시간을 만들고, 실행기가 받은
 * 만들기가 끝나기를 기다린 뒤 결과를 읽는다. 다른 검사에서는 추천이 꺼져 있고 이 검사만 켠다.
 */
@BackendIntegrationTest
@OverrideProperties({
    "assistant.starters.enabled=true",
    "assistant.starters.refresh-after=24h",
    "assistant.starters.retry-after-failure=10m"
})
class StarterSuggestionServiceTest {

    private static final CurrentUser DAD = new CurrentUser(81L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);
    private static final CurrentUser KID = new CurrentUser(82L, "kid@example.com", "아이", 1L, UserRole.MEMBER);
    private static final List<String> FOUR = List.of("일정 정리해 줘", "장보기 목록 만들어 줘", "날씨 알려 줘", "가계부 요약해 줘");

    @Autowired
    LiveProperties<StarterProperties> properties;

    @Autowired
    AgentService agentService;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executionRows;

    @Autowired
    ExecutionRecorder executions;

    @Autowired
    ModelTierService modelTiers;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    PriceCatalog prices;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final TrackingExecutor executor = new TrackingExecutor();
    private StarterSuggestionService service;
    private Agent family;

    @BeforeEach
    void setUp() {
        messages.deleteAll();
        conversations.deleteAll();
        executionRows.deleteAll();
        agents.deleteAll();
        stub().reset();
        family = agents.save(Agent.of(
                "starter-family",
                "가족",
                "starter-family",
                "http://runtime.test",
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.GROUP,
                DAD.id(),
                Instant.now()));
        service = new StarterSuggestionService(
                properties,
                agentService,
                conversations,
                messages,
                hermes,
                executions,
                modelTiers,
                objectMapper,
                limiter,
                clock,
                executor);
    }

    @Test
    @DisplayName("캐시가 비면 GENERATING 을 주고 만들기가 끝나면 READY 와 넷을 준다")
    void returnsGeneratingOnEmptyCacheThenReadyWithFourAfterBuild() {
        answerWith(json(FOUR));

        StarterSuggestions first = service.read(DAD, "starter-family");
        assertThat(first.status()).isEqualTo(StarterStatus.GENERATING);
        assertThat(first.prompts()).isEmpty();

        executor.awaitAll();

        StarterSuggestions second = service.read(DAD, "starter-family");
        assertThat(second.status()).isEqualTo(StarterStatus.READY);
        assertThat(second.prompts()).containsExactlyElementsOf(FOUR);
        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().profileName()).isEqualTo("starter-family");
    }

    @Test
    @DisplayName("추천 실행은 대화 없는 실행 줄로 남고 끝에 SUCCEEDED 다")
    void suggestionRunLeavesRunRowWithoutConversationEndingSucceeded() {
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(executionRows.findAll()).singleElement().satisfies(row -> {
            assertThat(row.conversationId()).isNull();
            assertThat(row.parentExecutionId()).isNull();
            assertThat(row.rootExecutionId()).isNull();
            assertThat(row.hermesSessionId()).isNull();
            assertThat(row.userId()).isEqualTo(DAD.id());
            assertThat(row.agentId()).isEqualTo(family.id());
            assertThat(row.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        });
    }

    @Test
    @DisplayName("에이전트 기본값이 있으면 Hermes 에 보낸 세 값이 실행 줄에 적히고 출처는 AGENT DEFAULT 다")
    void recordsSentAgentDefaultsOnRunRowWithAgentDefaultSource() {
        family.changeDefaultModel("example-provider", "example-agent", "medium");
        agents.save(family);
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.provider()).isEqualTo("example-provider");
            assertThat(command.model()).isEqualTo("example-agent");
            assertThat(command.reasoningEffort()).isEqualTo("medium");
        });
        assertThat(executionRows.findAll()).singleElement().satisfies(row -> {
            assertThat(row.provider()).isEqualTo("example-provider");
            assertThat(row.model()).isEqualTo("example-agent");
            assertThat(row.reasoningEffort()).isEqualTo("medium");
            assertThat(row.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.AGENT_DEFAULT);
        });
    }

    @Test
    @DisplayName("에이전트 기본값이 비면 Hermes 에 세 값을 보내지 않고 실행 줄의 세 값은 null 이며 출처는 UNKNOWN 이다")
    void sendsNothingAndRecordsUnknownSourceWhenAgentHasNoDefaults() {
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.provider()).isNull();
            assertThat(command.model()).isNull();
            assertThat(command.reasoningEffort()).isNull();
        });
        assertThat(executionRows.findAll()).singleElement().satisfies(row -> {
            assertThat(row.provider()).isNull();
            assertThat(row.model()).isNull();
            assertThat(row.reasoningEffort()).isNull();
            assertThat(row.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.UNKNOWN);
        });
    }

    /** 숨김 줄을 실제로 넣으면 같은 그룹을 쓰는 다른 검사의 기본값 실행이 막힌다. 그래서 판정만 대역으로 둔다. */
    @Test
    @DisplayName("숨김 판정이 거절하면 Hermes 에 제출하지 않고 실행 줄이 FAILED 와 MODEL HIDDEN 으로 남는다")
    void leavesFailedRunRowWithoutSubmittingWhenHiddenCheckRejects() {
        ModelTierService rejecting = mock(ModelTierService.class);
        when(rejecting.detachedChoice(any())).thenReturn(ModelChoice.defaults());
        doThrow(new ApiException(ErrorCode.MODEL_HIDDEN, "hidden"))
                .when(rejecting)
                .requireRunnable(any(), any(), any());
        service = new StarterSuggestionService(
                properties,
                agentService,
                conversations,
                messages,
                hermes,
                executions,
                rejecting,
                objectMapper,
                limiter,
                clock,
                executor);
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(stub().received()).as("Hermes 에 제출한 수").isEmpty();
        assertThat(executionRows.findAll()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(row.errorCode()).isEqualTo(ErrorCode.MODEL_HIDDEN.name());
        });
        assertThat(service.read(DAD, "starter-family"))
                .as("재시도 간격 안의 추천")
                .isEqualTo(new StarterSuggestions(List.of(), StarterStatus.NONE));
    }

    @Test
    @DisplayName("이력이 있으면 그 사용자의 지우지 않은 대화마다 첫 질문을 입력에 싣는다")
    void putsFirstQuestionOfEachLiveConversationIntoInput() {
        conversationOf(DAD, family, "이번 주 일정 알려 줘", "다음 질문은 싣지 않는다");
        conversationOf(DAD, family, "장보기 목록 정리해 줘");
        conversationOf(KID, family, "다른 사용자의 질문");
        Conversation deleted = conversationOf(DAD, family, "지운 대화의 질문");
        conversationWriter.deleteIfActive(deleted.id(), DAD.id(), Instant.now());
        Agent other = agents.save(Agent.of(
                "starter-other",
                "다른",
                "starter-other",
                "http://runtime.test",
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.GROUP,
                DAD.id(),
                Instant.now()));
        conversationOf(DAD, other, "다른 에이전트와 나눈 질문");
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        String input = onlyInput();
        assertThat(input.lines().findFirst()).contains(StarterSuggestionService.PROMPT_MARK);
        assertThat(input)
                .contains("이번 주 일정 알려 줘", "장보기 목록 정리해 줘", "JSON 문자열 배열")
                .doesNotContain("다른 사용자의 질문", "다음 질문은 싣지 않는다", "지운 대화의 질문", "다른 에이전트와 나눈 질문", "처음 해 볼 만한");
    }

    @Test
    @DisplayName("이력이 없으면 처음 해 볼 만한 요청을 묻는다")
    void asksForFirstRequestsToTryWhenNoHistory() {
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(onlyInput()).startsWith(StarterSuggestionService.PROMPT_MARK).contains("처음 해 볼 만한", "JSON 문자열 배열");
    }

    @Test
    @DisplayName("답이 JSON 이 아니면 NONE 이고 재시도 시간 안에는 다시 만들지 않는다")
    void returnsNoneAndSkipsRebuildWithinRetryWindowWhenAnswerIsNotJson() {
        answerWith("추천을 만들 수 없습니다");

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family"))
                .isEqualTo(new StarterSuggestions(List.of(), StarterStatus.NONE));
        clock.advance(Duration.ofMinutes(9));
        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.NONE);
        assertThat(stub().received()).as("재시도 시간 안의 제출 수").hasSize(1);
        assertThat(executionRows.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(ExecutionStatus.FAILED));

        clock.advance(Duration.ofMinutes(2));
        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.GENERATING);
        executor.awaitAll();
        assertThat(stub().received()).as("재시도 시간이 지난 뒤의 제출 수").hasSize(2);
    }

    @Test
    @DisplayName("대화를 마쳐 다시 만들다 실패하면 재시도 시간 안에는 다시 만들지 않고 이전 추천이 남는다")
    void keepsPreviousAndSkipsRebuildInRetryWindowAfterFailedRebuild() {
        answerWith(json(FOUR));
        service.read(DAD, "starter-family");
        executor.awaitAll();

        answerWith("추천을 만들 수 없습니다");
        clock.advance(Duration.ofHours(25));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("오래된 추천을 다시 만든 제출 수").hasSize(2);

        clock.advance(Duration.ofMinutes(9));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("재시도 시간 안의 제출 수").hasSize(2);
        assertThat(service.read(DAD, "starter-family")).isEqualTo(new StarterSuggestions(FOUR, StarterStatus.READY));

        clock.advance(Duration.ofMinutes(2));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("재시도 시간이 지난 뒤의 제출 수").hasSize(3);
        assertThat(service.read(DAD, "starter-family")).isEqualTo(new StarterSuggestions(FOUR, StarterStatus.READY));
    }

    /** 추천을 만든 뒤 실행 줄을 끝내다 실패해도 만들기 실패로 보지 않는다. 재시도 시간에 막히지 않는다. */
    @Test
    @DisplayName("실행 줄을 끝내다 실패해도 추천은 READY 이고 실행 줄은 실패로 바뀌지 않는다")
    void keepsReadyAndRunRowNotFailedWhenFinishingRunRowFails() {
        ExecutionRecorder failingRecorder = spy(executions);
        doThrow(new IllegalStateException("저장 실패")).when(failingRecorder).complete(any(), any(), any(), any());
        service = new StarterSuggestionService(
                properties,
                agentService,
                conversations,
                messages,
                hermes,
                failingRecorder,
                modelTiers,
                objectMapper,
                limiter,
                clock,
                executor);
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family")).isEqualTo(new StarterSuggestions(FOUR, StarterStatus.READY));
        assertThat(executionRows.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.status()).isNotEqualTo(ExecutionStatus.FAILED));

        clock.advance(Duration.ofHours(25));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("실패 기록이 없어 오래된 추천을 다시 만든 제출 수").hasSize(2);
    }

    @Test
    @DisplayName("다시 만들다 실패하면 이전 추천이 남는다")
    void keepsPreviousSuggestionsWhenRebuildFails() {
        answerWith(json(FOUR));
        service.read(DAD, "starter-family");
        executor.awaitAll();

        answerWith("[깨진 JSON");
        clock.advance(Duration.ofHours(25));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();

        assertThat(stub().received()).hasSize(2);
        assertThat(service.read(DAD, "starter-family")).isEqualTo(new StarterSuggestions(FOUR, StarterStatus.READY));
    }

    @Test
    @DisplayName("Hermes 가 실패해도 이전 추천이 남고 실행 줄은 실패로 남는다")
    void keepsPreviousSuggestionsAndRecordsRunFailureWhenHermesFails() {
        answerWith(json(FOUR));
        service.read(DAD, "starter-family");
        executor.awaitAll();

        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));
        clock.advance(Duration.ofHours(25));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family")).isEqualTo(new StarterSuggestions(FOUR, StarterStatus.READY));
        assertThat(executionRows.findAll())
                .extracting(row -> row.status())
                .containsExactlyInAnyOrder(ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "PROVIDER_BLOCKED", "STARTER_OUTPUT_INVALID"})
    @DisplayName("추천 실패와 잘못된 답은 사용량과 실제 모델 비용을 보존하고 재시도 시간 동안 NONE 을 준다")
    void preservesUsageAndCostForFailedResultAndInvalidOutput(String errorCode) {
        doReturn(Optional.of(new CatalogPrice(
                        new ModelPrice(new BigDecimal("5"), new BigDecimal("30"), new BigDecimal("0.5"), List.of()),
                        "test-pricing@2026-10-01")))
                .when(prices)
                .find("served-provider", "served-model");
        boolean invalidOutput = "STARTER_OUTPUT_INVALID".equals(errorCode);
        String error = "PROVIDER_BLOCKED".equals(errorCode)
                ? HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " every account is blocked"
                : null;
        stub().willReturn(new HermesRunResult(
                "starter-run",
                "starter-session",
                invalidOutput ? "completed" : "failed",
                invalidOutput ? "추천을 만들 수 없습니다" : json(FOUR),
                "echoed-model",
                "echoed-provider",
                error,
                new TokenUsage(1_000L, 800L, 500L, 1_500L),
                new SessionRuntime("served-model", "served-provider")));

        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.GENERATING);
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family"))
                .isEqualTo(new StarterSuggestions(List.of(), StarterStatus.NONE));
        clock.advance(Duration.ofMinutes(9));
        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.NONE);
        assertThat(stub().received()).hasSize(1);
        assertThat(executionRows.findAll()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(row.errorCode()).isEqualTo(errorCode);
            assertThat(row.hermesRunId()).isEqualTo("starter-run");
            assertThat(row.conversationId()).isNull();
            assertThat(row.provider()).isEqualTo("served-provider");
            assertThat(row.model()).isEqualTo("served-model");
            assertThat(row.inputTokens()).isEqualTo(1_000L);
            assertThat(row.cachedInputTokens()).isEqualTo(800L);
            assertThat(row.outputTokens()).isEqualTo(500L);
            assertThat(row.totalTokens()).isEqualTo(1_500L);
            assertThat(row.estimatedCostMicros()).isEqualTo(16_400L);
            assertThat(row.actualCostMicros()).isEqualTo(16_400L);
            assertThat(row.costCurrency()).isEqualTo("USD");
            assertThat(row.pricingVersion()).isEqualTo("test-pricing@2026-10-01");
            assertThat(row.finishedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("코드 펜스를 떼고 120자를 넘는 줄은 버리고 앞의 넷만 쓴다")
    void stripsCodeFenceDropsLinesOver120CharsAndUsesFirstFour() {
        String limit = "나".repeat(120);
        answerWith("```json\n" + json(List.of("첫째", "가".repeat(121), limit, "셋째", "넷째", "다섯째")) + "\n```");

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family").prompts()).containsExactly("첫째", limit, "셋째", "넷째");
    }

    @Test
    @DisplayName("빈 배열은 실패로 본다")
    void treatsEmptyArrayAsFailure() {
        answerWith("[]");

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.NONE);
    }

    @Test
    @DisplayName("같은 키를 만드는 동안 다시 읽어도 제출은 한 번이다")
    void submitsOnceEvenIfReadAgainWhileBuildingSameKey() throws InterruptedException {
        answerWith(json(FOUR));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub().beforeAwait(() -> {
            entered.countDown();
            awaitQuietly(release);
        });
        try {
            assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.GENERATING);
            assertThat(entered.await(5, TimeUnit.SECONDS))
                    .as("첫 만들기가 Hermes 제출에 닿았다")
                    .isTrue();

            StarterSuggestions whileGenerating = service.read(DAD, "starter-family");

            assertThat(whileGenerating).isEqualTo(new StarterSuggestions(List.of(), StarterStatus.GENERATING));
            assertThat(stub().received()).hasSize(1);
        } finally {
            release.countDown();
        }
        executor.awaitAll();
        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.READY);
        assertThat(stub().received()).hasSize(1);
    }

    @Test
    @DisplayName("대화를 마쳤을 때는 추천이 있고 오래됐을 때만 다시 만든다")
    void rebuildsOnlyWhenStaleAfterConversationEnds() {
        answerWith(json(FOUR));

        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("추천이 없을 때의 제출 수").isEmpty();

        service.read(DAD, "starter-family");
        executor.awaitAll();
        assertThat(stub().received()).hasSize(1);

        clock.advance(Duration.ofHours(23));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("refreshAfter 안의 제출 수").hasSize(1);

        clock.advance(Duration.ofHours(2));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("refreshAfter 가 지난 뒤의 제출 수").hasSize(2);
    }

    @Test
    @DisplayName("사용자마다 추천을 따로 만든다")
    void buildsSuggestionsPerUser() {
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(service.read(KID, "starter-family").status()).isEqualTo(StarterStatus.GENERATING);
        executor.awaitAll();
        assertThat(stub().received()).hasSize(2);
    }

    /** 꺼 둔 에이전트는 새 실행을 막는다. 볼 수는 있으므로 오류 대신 추천이 없다고 답한다. */
    @Test
    @DisplayName("꺼진 에이전트의 추천은 NONE 이고 만들지 않는다")
    void returnsNoneAndDoesNotBuildForDisabledAgent() {
        answerWith(json(FOUR));
        service.read(DAD, "starter-family");
        executor.awaitAll();
        assertThat(stub().received()).hasSize(1);

        family.changeAccess(false, AgentVisibility.GROUP, DAD.id());
        family = agents.save(family);
        clock.advance(Duration.ofHours(25));

        assertThat(service.read(DAD, "starter-family"))
                .isEqualTo(new StarterSuggestions(List.of(), StarterStatus.NONE));
        assertThat(service.read(KID, "starter-family"))
                .isEqualTo(new StarterSuggestions(List.of(), StarterStatus.NONE));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("꺼진 뒤의 제출 수").hasSize(1);
    }

    @Test
    @DisplayName("볼 수 없는 에이전트의 추천은 AGENT NOT FOUND 이고 만들지 않는다")
    void returnsAgentNotFoundAndDoesNotBuildForInvisibleAgent() {
        agents.save(Agent.of(
                "starter-private",
                "개인",
                "starter-private",
                "http://runtime.test",
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.PRIVATE,
                DAD.id(),
                Instant.now()));
        answerWith(json(FOUR));

        assertThatThrownBy(() -> service.read(KID, "starter-private"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        executor.awaitAll();
        assertThat(stub().received()).isEmpty();
    }

    private Conversation conversationOf(CurrentUser user, Agent agent, String first, String... later) {
        Conversation conversation =
                conversations.save(Conversation.startedBy(user.id(), first, agent.id(), Instant.now()));
        messages.save(ChatMessage.fromUser(conversation.id(), user.id(), first, Instant.now()));
        messages.save(ChatMessage.fromAssistant(conversation.id(), "답", null, Instant.now()));
        for (String text : later) {
            messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text, Instant.now()));
        }
        return conversation;
    }

    private void answerWith(String output) {
        stub().willAnswer(command ->
                HermesRunResult.of("starter-run", null, "completed", output, "model", "provider", TokenUsage.empty()));
    }

    private String json(List<String> prompts) {
        return objectMapper.writeValueAsString(prompts);
    }

    private String onlyInput() {
        assertThat(stub().received()).hasSize(1);
        HermesRunCommand command = stub().received().getFirst();
        return command.input();
    }

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 받은 만들기를 virtual thread 로 돌리고, 검사가 그것들이 끝나기를 기다리게 한다. */
    private static final class TrackingExecutor implements Executor {
        private final List<CompletableFuture<Void>> tasks = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(CompletableFuture.runAsync(
                    command, runnable -> Thread.ofVirtual().start(runnable)));
        }

        void awaitAll() {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
