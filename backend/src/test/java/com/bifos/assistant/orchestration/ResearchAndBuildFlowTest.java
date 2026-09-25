package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.MessageRole;
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
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.usage.presentation.UsageController;
import com.bifos.assistant.usage.presentation.UsageDtos.ExecutionView;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 흐름 하나가 실행 넷을 남기고 그 넷이 부모의 경계를 물려받는 것을 고정한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(ResearchAndBuildFlowTest.StubRuntime.class)
class ResearchAndBuildFlowTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    /** Chief 에게 준 지시에만 들어 있는 말이다. 대역이 이것으로 단계를 가려낸다. */
    private static final String CHIEF_MARK = "조사할 것과 만들 것을 나눈다";

    private static final String SPLIT_JSON =
            "{\"research\":\"전기차 보조금\",\"build\":\"비교 표\"}";

    /** 금액을 실제로 환산하려면 가격표가 있어야 한다. 표본 가격표를 가리킨다. */
    @DynamicPropertySource
    static void pointAtTheSampleCatalog(DynamicPropertyRegistry registry) {
        registry.add("assistant.pricing.catalog-path", () -> sampleCatalog().toString());
    }

    private static Path sampleCatalog() {
        try {
            return Path.of(
                    ResearchAndBuildFlowTest.class
                            .getResource("/pricing/models-dev-sample.json")
                            .toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 이 검사가 쓰는 에이전트와 사용자다. 다른 검사 클래스와 겹치지 않는 이름으로 둔다. */
    private static final String MY_AGENT = "flow-dad";
    private static final String MY_EMAIL = "flow-dad@example.com";

    @Autowired ChatService chat;
    @Autowired UsageController usage;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired AgentModelSelector modelSelector;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired MemoryRepository memoryRepository;
    @Autowired HermesRunsClient hermes;
    @MockitoSpyBean TurnCancellation turns;

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
        agents.findByCode(MY_AGENT).ifPresent(agents::delete);
        users.findByEmail(MY_EMAIL).ifPresent(users::delete);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 흐름이 붙은 에이전트 하나를 가진 사용자를 만든다. */
    private CurrentUser member(String email, String agentCode, String flow) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        Agent agent = Agent.of(
                agentCode,
                agentCode,
                agentCode,
                "http://agent-runtime.test/p/" + agentCode,
                "anthropic",
                "example-model-large",
                CostMode.API,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id());
        agent.assignFlow(flow);
        Agent saved = agents.save(agent);
        modelSelector.seedFirst(saved, new ModelOption("anthropic", "example-model-large"));
        return new CurrentUser(
                user.id(), user.email(), user.displayName(), user.familyId(), user.role());
    }

    private static HermesRunResult completed(String runId, String output) {
        return HermesRunResult.of(
                runId, "sess-" + runId, "completed", output, "example-model-large", "anthropic",
                new TokenUsage(100L, 0L, 20L, 120L));
    }

    /** 단계마다 다른 답을 돌려준다. 나란히 도는 둘의 순서가 정해지지 않아 지시로 가려낸다. */
    private void hermesAnswersEachStep(String chiefOutput) {
        stub().willAnswer(command -> {
            String input = command.input();
            if (input.contains(CHIEF_MARK)) {
                return completed("run-chief", chiefOutput);
            }
            if (input.contains("조사해")) {
                return completed("run-researcher", "조사한 것");
            }
            if (input.contains("만든다")) {
                return completed("run-engineer", "만든 것");
            }
            return completed("run-synthesizer", "합친 답");
        });
    }

    private List<AgentExecution> executionsOf(CurrentUser user) {
        return executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 20));
    }

    private List<ExecutionEventType> eventTypes(Long executionId) {
        return executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId)).stream()
                .map(event -> event.eventType()).toList();
    }

    private List<ExecutionEventType> cancelledEvents(Long executionId) {
        return eventTypes(executionId).stream()
                .filter(type -> type == ExecutionEventType.RUN_CANCELLED).toList();
    }

    @Test
    void 흐름_한_번이_실행_넷을_남기고_Chief가_뿌리다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);

        ChatTurn turn = chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT);

        assertThat(turn.assistantText()).isEqualTo("합친 답");
        List<AgentExecution> all = executionsOf(dad);
        assertThat(all).hasSize(4);

        AgentExecution chief = executions.findById(turn.executionId()).orElseThrow();
        assertThat(chief.parentExecutionId()).isNull();
        assertThat(chief.rootExecutionId()).isNull();
        assertThat(chief.status()).isEqualTo(ExecutionStatus.SUCCEEDED);

        List<AgentExecution> childExecutions =
                all.stream().filter(it -> !Objects.equals(it.id(), chief.id())).toList();
        assertThat(childExecutions).hasSize(3);
        assertThat(childExecutions)
                .allSatisfy(child -> {
                    assertThat(child.rootExecutionId()).isEqualTo(chief.id());
                    assertThat(child.parentExecutionId()).isEqualTo(chief.id());
                    assertThat(child.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
                });
        var summary = chat.activitySummaries(chat.history(dad, turn.conversationId())).get(chief.id());
        assertThat(summary.subagentCount()).isEqualTo(3);
        assertThat(summary.durationMs()).isGreaterThan(chief.latencyMs());
        // 밀리초로 각각 바꾼 뒤 빼면 밀리초 경계에서 1 이 어긋난다. 요약과 같은 방법으로 잰다.
        assertThat(summary.durationMs()).isEqualTo(Duration.between(chief.startedAt(), all.stream()
                .map(AgentExecution::finishedAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElseThrow()).toMillis());
    }

    @Test
    void 경계_자식_실행의_userId가_전부_부모와_같다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);

        chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT);

        assertThat(executionsOf(dad)).hasSize(4).allSatisfy(execution ->
                assertThat(execution.userId()).isEqualTo(dad.id()));
    }

    @Test
    void 스트림이_네_단계의_사건을_순서대로_낸다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "전기차를 사는 게 나을까?", MY_AGENT, relayed::add);

        assertThat(relayed.stream()
                        .filter(event -> "step".equals(event.type()))
                        .map(event -> event.stepName() + ":" + event.stepState())
                        .toList())
                .containsExactly(
                        "chief:started",
                        "chief:completed",
                        "researcher:started",
                        "engineer:started",
                        "researcher:completed",
                        "engineer:completed",
                        "synthesizer:started",
                        "synthesizer:completed");
        assertThat(relayed.getLast().type()).isEqualTo("done");
        assertThat(relayed.stream().filter(event -> "started".equals(event.type())).toList())
                .singleElement()
                .satisfies(started -> {
                    assertThat(started.conversationId()).isEqualTo(relayed.getLast().conversationId());
                    assertThat(started.executionId()).isEqualTo(relayed.getLast().executionId());
                });
    }

    @Test
    void Chief가_도는_동안_대화를_지워도_끝난_뒤에_지운_채다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        List<ChatEvent> relayed = new ArrayList<>();
        stub().willAnswer(command -> {
            if (command.input().contains(CHIEF_MARK)) {
                ChatEvent started = relayed.stream()
                        .filter(event -> "started".equals(event.type())).findFirst().orElseThrow();
                chat.delete(dad, started.conversationId());
                return completed("run-chief", "{\"research\":\"\",\"build\":\"\"}");
            }
            return completed("run-other", "답");
        });

        chat.stream(dad, null, "첫 질문", MY_AGENT, relayed::add);

        ChatEvent started = relayed.stream()
                .filter(event -> "started".equals(event.type())).findFirst().orElseThrow();
        assertThat(conversations.findById(started.conversationId()).orElseThrow().deletedAt())
                .isNotNull();
        assertThat(chat.conversationsOf(dad)).isEmpty();
    }

    @Test
    void Researcher가_실패하면_Engineer를_기다린_뒤_멈추고_Synthesizer는_돌지_않는다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        stub().willAnswer(command -> {
            String input = command.input();
            if (input.contains(CHIEF_MARK)) {
                return completed("run-chief", SPLIT_JSON);
            }
            if (input.contains("조사해")) {
                return HermesRunResult.of(
                        "run-researcher", null, "failed", null, "example-model-large", "anthropic",
                        TokenUsage.empty());
            }
            if (input.contains("만든다")) {
                return completed("run-engineer", "만든 것");
            }
            return completed("run-synthesizer", "합친 답");
        });

        assertThatThrownBy(() -> chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.ORCHESTRATION_STEP_FAILED);

        List<AgentExecution> all = executionsOf(dad);
        // Chief 와 Researcher 와 Engineer 셋뿐이다. Synthesizer 는 돌지 않는다.
        assertThat(all).hasSize(3);
        AgentExecution chief =
                all.stream().filter(it -> it.rootExecutionId() == null).findFirst().orElseThrow();
        assertThat(chief.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(chief.errorCode()).isEqualTo("FAILED");
        // 한쪽이 실패해도 다른 쪽을 끝까지 기다린다.
        assertThat(all)
                .filteredOn(it -> it.status() == ExecutionStatus.SUCCEEDED)
                .singleElement()
                .satisfies(engineer -> assertThat(engineer.parentExecutionId()).isEqualTo(chief.id()));
    }

    @Test
    void Chief의_답을_파싱하지_못하면_자식을_하나도_만들지_않고_흐름이_실패한다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep("JSON 이 아닌 그냥 문장이다");

        assertThatThrownBy(() -> chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.ORCHESTRATION_CONTRACT_BROKEN);

        assertThat(executionsOf(dad)).singleElement().satisfies(chief -> {
            assertThat(chief.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(chief.errorCode()).isEqualTo("ORCHESTRATION_CONTRACT_BROKEN");
        });
        assertThat(messages.findAll()).singleElement().satisfies(message ->
                assertThat(message.role()).isEqualTo(MessageRole.USER));
    }

    @Test
    void 나눌_것이_둘_다_비면_Chief의_답이_최종_답이_되고_실행이_하나만_남는다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep("{\"research\":\"\",\"build\":\"\"}");

        ChatTurn turn = chat.send(dad, null, "안녕", MY_AGENT);

        assertThat(turn.assistantText()).isEqualTo("{\"research\":\"\",\"build\":\"\"}");
        assertThat(executionsOf(dad)).singleElement().satisfies(chief -> {
            assertThat(chief.id()).isEqualTo(turn.executionId());
            assertThat(chief.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        });
    }

    @Test
    void 사용량_목록에_뿌리_하나만_나오고_월_비용_합계는_넷을_모두_더한다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);
        ChatTurn turn = chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(dad, null));

        List<ExecutionView> listed = usage.myExecutions(50);

        assertThat(listed).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(turn.executionId());
            assertThat(view.hasChildren()).isTrue();
        });

        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);
        MonthlyCost total = executions.sumCostBetween(dad.id(), from, to);
        long everyRow = executionsOf(dad).stream()
                .map(AgentExecution::estimatedCostMicros)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        assertThat(total.pricedExecutions()).isEqualTo(4);
        assertThat(total.totalMicros()).isEqualTo(everyRow);
    }

    @Test
    void 흐름이_없는_에이전트는_지금처럼_실행_하나만_남긴다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, null);
        stub().willReturn(completed("run-1", "그냥 답"));

        ChatTurn turn = chat.send(dad, null, "안녕", MY_AGENT);

        assertThat(turn.assistantText()).isEqualTo("그냥 답");
        assertThat(executionsOf(dad)).singleElement().satisfies(execution ->
                assertThat(execution.rootExecutionId()).isNull());
    }

    /** 나란히 도는 두 단계가 실제로 함께 떠 있는지 본다. 줄서면 이 흐름의 값이 사라진다. */
    @Test
    void Researcher와_Engineer가_실제로_함께_떠_있다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        java.util.concurrent.CountDownLatch bothSubmitted = new java.util.concurrent.CountDownLatch(2);
        stub().willAnswer(command -> {
            String input = command.input();
            if (input.contains(CHIEF_MARK)) {
                return completed("run-chief", SPLIT_JSON);
            }
            if (input.contains("조사해") || input.contains("만든다")) {
                bothSubmitted.countDown();
                await(bothSubmitted);
                return completed(input.contains("조사해") ? "run-researcher" : "run-engineer", "답");
            }
            return completed("run-synthesizer", "합친 답");
        });

        chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT);

        assertThat(bothSubmitted.getCount()).isZero();
    }

    @Test
    void Chief가_도는_중에_멈추면_Chief를_멈추고_자식을_시작하지_않는다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        List<ChatEvent> relayed = new ArrayList<>();
        stub().willAnswer(command -> HermesRunResult.of(
                "run-chief",
                "sess-chief",
                "cancelled",
                "{\"research\":\"전기차 보조금\",\"build\":\"비교 표\"}",
                "example-model-large",
                "anthropic",
                TokenUsage.empty()));
        stub().beforeAwait(() -> {
            ChatEvent started = relayed.stream()
                    .filter(event -> "started".equals(event.type()))
                    .findFirst()
                    .orElseThrow();
            chat.stop(dad, started.executionId());
        });

        chat.stream(dad, null, "전기차를 사는 게 나을까?", MY_AGENT, relayed::add);

        assertThat(stub().stopped()).containsExactly("run-chief");
        assertThat(stub().received()).singleElement();
        assertThat(executionsOf(dad)).singleElement().satisfies(root ->
                assertThat(root.status()).isEqualTo(ExecutionStatus.CANCELLED));
        ChatEvent started = relayed.stream().filter(event -> "started".equals(event.type())).findFirst().orElseThrow();
        assertThat(cancelledEvents(started.executionId())).containsExactly(ExecutionEventType.RUN_CANCELLED);
        assertThat(conversations.findById(started.conversationId()).orElseThrow().hermesSessionId())
                .isEqualTo("sess-chief");
        assertThat(relayed.getLast().type()).isEqualTo("stopped");
    }

    @Test
    void Chief가_끝난_뒤_자식_제출_전에_멈추면_자식을_만들지_않는다() throws Exception {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);
        List<ChatEvent> relayed = new ArrayList<>();
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> stop = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            chat.stream(dad, null, "전기차를 사는 게 나을까?", MY_AGENT, event -> {
                relayed.add(event);
                if ("step".equals(event.type()) && "chief".equals(event.stepName())
                        && "completed".equals(event.stepState())) {
                    Long rootId = relayed.stream().filter(it -> "started".equals(it.type())).findFirst()
                            .orElseThrow().executionId();
                    stop.set(worker.submit(() -> chat.stop(dad, rootId)));
                    awaitCancellation(rootId);
                }
            });

            stop.get().get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(stub().received()).singleElement();
            assertThat(executionsOf(dad)).singleElement().satisfies(root ->
                    assertThat(root.status()).isEqualTo(ExecutionStatus.CANCELLED));
            assertThat(relayed.getLast().type()).isEqualTo("stopped");
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void 자식_runId가_저장된_뒤_trackRun_전에_중지해도_그_자식을_멈춘다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        java.util.concurrent.atomic.AtomicBoolean intercepted = new java.util.concurrent.atomic.AtomicBoolean();
        stub().willAnswer(command -> {
            if (command.input().contains(CHIEF_MARK)) return completed("run-chief", SPLIT_JSON);
            return HermesRunResult.of("run-child", null, "cancelled", "", "example-model-large", "anthropic", TokenUsage.empty());
        });
        doAnswer(invocation -> {
            String runId = invocation.getArgument(3);
            if ("run-child".equals(runId) && intercepted.compareAndSet(false, true)) {
                Long rootId = executionsOf(dad).stream().filter(execution -> execution.rootExecutionId() == null)
                        .findFirst().orElseThrow().id();
                chat.stop(dad, rootId);
            }
            return invocation.callRealMethod();
        }).when(turns).trackRun(any(), any(), any(), any());

        chat.stream(dad, null, "전기차를 사는 게 나을까?", MY_AGENT, event -> {});

        assertThat(intercepted).isTrue();
        assertThat(stub().stopped()).contains("run-child");
    }

    @Test
    void 자식_둘이_도는_중에_멈추면_둘을_멈추고_합치기를_시작하지_않는다() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        List<ChatEvent> relayed = new ArrayList<>();
        java.util.concurrent.CountDownLatch childrenSubmitted = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch childrenAwaiting = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch stopSent = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger awaitCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Instant> chiefFinishedAt = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Long> chiefLatencyMs = new java.util.concurrent.atomic.AtomicReference<>();
        stub().willAnswer(command -> {
            String input = command.input();
            if (input.contains(CHIEF_MARK)) {
                return completed("run-chief", SPLIT_JSON);
            }
            if (input.contains("조사해") || input.contains("만든다")) {
                childrenSubmitted.countDown();
                await(childrenSubmitted);
                return HermesRunResult.of(
                        input.contains("조사해") ? "run-researcher" : "run-engineer",
                        null,
                        "cancelled",
                        null,
                        "example-model-large",
                        "anthropic",
                        TokenUsage.empty());
            }
            return completed("run-synthesizer", "합친 답");
        });
        stub().beforeAwait(() -> {
            if (awaitCalls.incrementAndGet() == 1) return;
            childrenAwaiting.countDown();
            await(childrenAwaiting);
            if (stopped.compareAndSet(false, true)) {
                try {
                    ChatEvent started = relayed.stream()
                            .filter(event -> "started".equals(event.type()))
                            .findFirst()
                            .orElseThrow();
                    AgentExecution chief = executions.findById(started.executionId()).orElseThrow();
                    chiefFinishedAt.set(chief.finishedAt());
                    chiefLatencyMs.set(chief.latencyMs());
                    chat.stop(dad, started.executionId());
                } finally {
                    stopSent.countDown();
                }
            } else {
                await(stopSent);
            }
        });

        chat.stream(dad, null, "전기차를 사는 게 나을까?", MY_AGENT, relayed::add);

        assertThat(stub().stopped()).containsExactlyInAnyOrder("run-researcher", "run-engineer");
        assertThat(stub().received()).hasSize(3).noneMatch(command ->
                command.input().contains("중간 산출물을 합쳐"));
        assertThat(executionsOf(dad))
                .filteredOn(execution -> execution.rootExecutionId() == null)
                .singleElement()
                .satisfies(root -> {
                    assertThat(root.status()).isEqualTo(ExecutionStatus.CANCELLED);
                    assertThat(root.totalTokens()).isEqualTo(120L);
                    assertThat(root.finishedAt()).isEqualTo(chiefFinishedAt.get());
                    assertThat(root.latencyMs()).isEqualTo(chiefLatencyMs.get());
                    assertThat(root.estimatedCostMicros()).isNotNull();
                    assertThat(cancelledEvents(root.id())).containsExactly(ExecutionEventType.RUN_CANCELLED);
                });
        assertThat(executionsOf(dad)).filteredOn(execution -> execution.rootExecutionId() != null)
                .allSatisfy(child -> assertThat(cancelledEvents(child.id()))
                        .containsExactly(ExecutionEventType.RUN_CANCELLED));
        assertThat(relayed.getLast().type()).isEqualTo("stopped");
    }

    /** 둘 다 제출될 때까지 기다린다. 줄서면 여기서 끝나지 않고 검사가 실패한다. */
    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 단계가 함께 떠 있지 않다");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void awaitCancellation(Long executionId) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (turns.isCancelled(executionId)) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("중지 요청이 turn 에 등록되지 않았다");
    }
}
