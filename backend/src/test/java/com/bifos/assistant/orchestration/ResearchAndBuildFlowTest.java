package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpCaller;
import com.bifos.assistant.mcp.application.McpCallerResolver;
import com.bifos.assistant.mcp.application.McpPrincipal;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.ResearchAndBuildFlow;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.usage.presentation.UsageController;
import com.bifos.assistant.usage.presentation.UsageDtos.ExecutionView;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
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

    private static final String SPLIT_JSON = "{\"research\":\"전기차 보조금\",\"build\":\"비교 표\"}";

    /** 금액을 실제로 환산하려면 가격표가 있어야 한다. 표본 가격표를 가리킨다. */
    @DynamicPropertySource
    static void pointAtTheSampleCatalog(DynamicPropertyRegistry registry) {
        registry.add("assistant.pricing.catalog-path", () -> sampleCatalog().toString());
    }

    private static Path sampleCatalog() {
        try {
            return Path.of(ResearchAndBuildFlowTest.class
                    .getResource("/pricing/models-dev-sample.json")
                    .toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 이 검사가 쓰는 에이전트와 사용자다. 다른 검사 클래스와 겹치지 않는 이름으로 둔다. */
    private static final String MY_AGENT = "flow-dad";

    private static final String MY_EMAIL = "flow-dad@example.com";

    @Autowired
    ChatService chat;

    @Autowired
    UsageController usage;

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
    MemoryRepository memoryRepository;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    AgentTokenService agentTokens;

    @Autowired
    McpCallerResolver callerResolver;

    @MockitoSpyBean
    TurnCancellation turns;

    /** 단계가 돌려준 Hermes run 번호별로 그 단계가 받은 session 을 모은다. 실행 줄을 run 번호로 짝짓는 데 쓴다. */
    private final Map<String, String> sentSessionByRunId = new ConcurrentHashMap<>();

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
        sentSessionByRunId.clear();
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
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, Instant.now()));
        Agent agent = Agent.of(
                agentCode,
                agentCode,
                agentCode,
                "http://agent-runtime.test/p/" + agentCode,
                CostMode.API,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                Instant.now());
        agent.assignFlow(flow);
        agents.save(agent);
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private static HermesRunResult completed(String runId, String output) {
        return HermesRunResult.of(
                runId,
                "sess-" + runId,
                "completed",
                output,
                "example-model-large",
                "anthropic",
                new TokenUsage(100L, 0L, 20L, 120L));
    }

    /** 단계마다 다른 답을 돌려준다. 나란히 도는 둘의 순서가 정해지지 않아 지시로 가려낸다. */
    private void hermesAnswersEachStep(String chiefOutput) {
        stub().willAnswer(command -> {
            HermesRunResult result = answerFor(command.input(), chiefOutput);
            sentSessionByRunId.put(result.runId(), command.sessionId());
            return result;
        });
    }

    private static HermesRunResult answerFor(String input, String chiefOutput) {
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
    }

    private List<AgentExecution> executionsOf(CurrentUser user) {
        return executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 20));
    }

    private List<ExecutionEventType> eventTypes(Long executionId) {
        return executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId)).stream()
                .map(event -> event.eventType())
                .toList();
    }

    /** 사건이 싣는 공개 식별자로 대화를 찾는다. 지운 대화도 찾아야 해서 주인 확인을 거치지 않는다. */
    private Conversation conversationOf(UUID publicId) {
        return conversations.findAll().stream()
                .filter(conversation -> publicId.equals(conversation.publicId()))
                .findFirst()
                .orElseThrow();
    }

    private List<ExecutionEventType> cancelledEvents(Long executionId) {
        return eventTypes(executionId).stream()
                .filter(type -> type == ExecutionEventType.RUN_CANCELLED)
                .toList();
    }

    @Test
    @DisplayName("흐름 한 번이 실행 넷을 남기고 Chief가 루트다")
    void oneFlowLeavesFourRunsAndChiefIsRoot() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);

        ChatTurn turn = chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT);

        assertThat(turn.assistantText()).isEqualTo("합친 답");
        Conversation conversation =
                conversations.findById(turn.conversationId()).orElseThrow();
        assertThat(stub().received()).hasSize(4).allSatisfy(command -> {
            if (command.input().contains(CHIEF_MARK)) {
                assertThat(command.input()).contains("요청:\n[결과물 폴더]\n");
            } else {
                assertThat(command.input()).startsWith("[결과물 폴더]\n");
            }
            assertThat(command.input()).contains("대화 식별자: " + conversation.publicId() + "\n");
        });
        assertThat(messages.findAll().stream().filter(message -> message.role() == MessageRole.USER))
                .singleElement()
                .satisfies(message -> assertThat(message.content()).isEqualTo("전기차를 사는 게 나을까?"));
        List<AgentExecution> all = executionsOf(dad);
        assertThat(all).hasSize(4);

        AgentExecution chief = executions.findById(turn.executionId()).orElseThrow();
        assertThat(chief.parentExecutionId()).isNull();
        assertThat(chief.rootExecutionId()).isNull();
        assertThat(chief.status()).isEqualTo(ExecutionStatus.SUCCEEDED);

        List<AgentExecution> childExecutions =
                all.stream().filter(it -> !Objects.equals(it.id(), chief.id())).toList();
        assertThat(childExecutions).hasSize(3);
        assertThat(childExecutions).allSatisfy(child -> {
            assertThat(child.rootExecutionId()).isEqualTo(chief.id());
            assertThat(child.parentExecutionId()).isEqualTo(chief.id());
            assertThat(child.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        });
        var summary =
                chat.activitySummaries(chat.history(dad, turn.conversationId())).get(chief.id());
        assertThat(summary.subagentCount()).isEqualTo(3);
        assertThat(summary.durationMs()).isGreaterThan(chief.latencyMs());
        // 밀리초로 각각 바꾼 뒤 빼면 밀리초 경계에서 1 이 어긋난다. 요약과 같은 방법으로 잰다.
        assertThat(summary.durationMs())
                .isEqualTo(Duration.between(
                                chief.startedAt(),
                                all.stream()
                                        .map(AgentExecution::finishedAt)
                                        .filter(Objects::nonNull)
                                        .max(Instant::compareTo)
                                        .orElseThrow())
                        .toMillis());
    }

    @Test
    @DisplayName("Chief 실행 줄에는 대화의 루트 session이 적히고 하위 실행 줄에는 각자 보낸 새 session이 적힌다")
    void chiefRowHasConversationRootSessionAndChildRowsHaveOwnNewSessions() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);

        ChatTurn turn = chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT);

        Conversation conversation =
                conversations.findById(turn.conversationId()).orElseThrow();
        String root = conversation.hermesRootSessionId();
        assertThat(root).startsWith("fos-");
        assertThat(stub().received())
                .filteredOn(command -> command.input().contains(CHIEF_MARK))
                .singleElement()
                .satisfies(command -> assertThat(command.sessionId()).isEqualTo(root));
        AgentExecution chief = executions.findById(turn.executionId()).orElseThrow();
        assertThat(chief.hermesSessionId()).as("Chief 실행 줄의 session").isEqualTo(root);
        List<AgentExecution> children = executionsOf(dad).stream()
                .filter(it -> !Objects.equals(it.id(), chief.id()))
                .toList();
        assertThat(children).hasSize(3).allSatisfy(child -> {
            assertThat(child.hermesSessionId())
                    .as("하위 실행 줄의 session")
                    .startsWith("fos-")
                    .isNotEqualTo(root);
            // 나란히 도는 단계의 순서에 기대지 않고 Hermes run 번호로 그 실행이 보낸 session 을 찾는다.
            assertThat(sentSessionByRunId.get(child.hermesRunId()))
                    .as("하위 실행 %s 이 Hermes 에 보낸 session", child.hermesRunId())
                    .isEqualTo(child.hermesSessionId());
        });
        assertThat(children.stream().map(AgentExecution::hermesSessionId).distinct())
                .as("하위 실행 줄의 session 은 서로 다르다")
                .hasSize(3);
    }

    /**
     * 하위 실행 안의 MCP 호출은 그 하위 실행의 session 으로 서명한다. 그 session 으로 찾은 부모가 도는 하위 실행 줄이고
     * 요청자가 흐름을 시작한 사용자여야 한다.
     *
     * <p>하위 실행이 부모의 session 을 보내면 부모 자리에 Chief 가 잡히고, session 이 비면 요청자를 정하지 못한다.
     */
    @Test
    @DisplayName("하위 실행 안의 MCP 호출은 그 하위 실행 줄을 부모로 삼고 흐름을 시작한 사용자로 돈다")
    void mcpCallInChildRunTakesChildRowAsParentAndRunsAsFlowStarter() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        String rawToken = agentTokens.issue(MY_AGENT, "flow-mcp").rawToken();
        McpPrincipal principal = agentTokens.authenticate(rawToken);
        Map<String, Object> resolvedByRunId = new ConcurrentHashMap<>();
        stub().willAnswer(command -> {
            HermesRunResult result = answerFor(command.input(), SPLIT_JSON);
            if (!command.input().contains(CHIEF_MARK)) {
                // 실행 줄이 RUNNING 인 동안 profile 플러그인이 서명하듯 그 명령의 session 을 루트와 session 으로 삼는다.
                String session = command.sessionId();
                try {
                    resolvedByRunId.put(
                            result.runId(),
                            callerResolver.resolve(
                                    principal, "memory_read", McpCallSigner.context(rawToken, "memory_read", session)));
                } catch (RuntimeException ex) {
                    resolvedByRunId.put(result.runId(), ex);
                }
            }
            return result;
        });

        ChatTurn turn = chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT);

        AgentExecution chief = executions.findById(turn.executionId()).orElseThrow();
        List<AgentExecution> children = executionsOf(dad).stream()
                .filter(it -> !Objects.equals(it.id(), chief.id()))
                .toList();
        assertThat(children).hasSize(3).allSatisfy(child -> {
            Object resolved = resolvedByRunId.get(child.hermesRunId());
            assertThat(resolved)
                    .as("하위 실행 %s 안의 MCP 호출이 정한 요청자", child.hermesRunId())
                    .isInstanceOf(McpCaller.class);
            McpCaller caller = (McpCaller) resolved;
            assertThat(caller.user().id())
                    .as("하위 실행 %s 의 요청자", child.hermesRunId())
                    .isEqualTo(dad.id());
            assertThat(caller.originExecution().id())
                    .as("하위 실행 %s 의 MCP 부모", child.hermesRunId())
                    .isEqualTo(child.id());
            assertThat(caller.originExecution().parentExecutionId())
                    .as("MCP 부모의 부모")
                    .isEqualTo(chief.id());
            assertThat(caller.originExecution().status()).as("부모를 찾을 때의 상태").isEqualTo(ExecutionStatus.RUNNING);
        });
    }

    @Test
    @DisplayName("경계 자식 실행의 userId가 전부 부모와 같다")
    void boundaryAllChildRunUserIdsEqualParents() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);

        chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT);

        assertThat(executionsOf(dad))
                .hasSize(4)
                .allSatisfy(execution -> assertThat(execution.userId()).isEqualTo(dad.id()));
    }

    @Test
    @DisplayName("스트림이 네 단계의 사건을 순서대로 낸다")
    void streamEmitsEventsOfFourStagesInOrder() {
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
        assertThat(relayed.stream()
                        .filter(event -> "started".equals(event.type()))
                        .toList())
                .singleElement()
                .satisfies(started -> {
                    assertThat(started.conversationId())
                            .isEqualTo(relayed.getLast().conversationId());
                    assertThat(started.executionId())
                            .isEqualTo(relayed.getLast().executionId());
                });
    }

    @Test
    @DisplayName("Chief가 도는 동안 대화를 지워도 끝난 뒤에 지운 채다")
    void conversationDeletedWhileChiefRunsStaysDeletedAfterEnd() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        List<ChatEvent> relayed = new ArrayList<>();
        stub().willAnswer(command -> {
            if (command.input().contains(CHIEF_MARK)) {
                ChatEvent started = relayed.stream()
                        .filter(event -> "started".equals(event.type()))
                        .findFirst()
                        .orElseThrow();
                chat.delete(dad, conversationOf(started.conversationId()).id());
                return completed("run-chief", "{\"research\":\"\",\"build\":\"\"}");
            }
            return completed("run-other", "답");
        });

        chat.stream(dad, null, "첫 질문", MY_AGENT, relayed::add);

        ChatEvent started = relayed.stream()
                .filter(event -> "started".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(conversationOf(started.conversationId()).deletedAt()).isNotNull();
        assertThat(chat.conversationsOf(dad, null, 100).items()).isEmpty();
    }

    @Test
    @DisplayName("Researcher가 실패하면 Engineer를 기다린 뒤 멈추고 Synthesizer는 돌지 않는다")
    void researcherFailureWaitsForEngineerThenStopsAndSkipsSynthesizer() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        stub().willAnswer(command -> {
            String input = command.input();
            if (input.contains(CHIEF_MARK)) {
                return completed("run-chief", SPLIT_JSON);
            }
            if (input.contains("조사해")) {
                return HermesRunResult.of(
                        "run-researcher", null, "failed", null, "example-model-large", "anthropic", TokenUsage.empty());
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
        AgentExecution chief = all.stream()
                .filter(it -> it.rootExecutionId() == null)
                .findFirst()
                .orElseThrow();
        assertThat(chief.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(chief.errorCode()).isEqualTo("FAILED");
        // 한쪽이 실패해도 다른 쪽을 끝까지 기다린다.
        assertThat(all)
                .filteredOn(it -> it.status() == ExecutionStatus.SUCCEEDED)
                .singleElement()
                .satisfies(engineer -> assertThat(engineer.parentExecutionId()).isEqualTo(chief.id()));
    }

    @Test
    @DisplayName("Chief의 답을 파싱하지 못하면 자식을 하나도 만들지 않고 흐름이 실패한다")
    void createsNoChildAndFailsFlowWhenChiefAnswerCannotBeParsed() {
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
        assertThat(messages.findAll())
                .singleElement()
                .satisfies(message -> assertThat(message.role()).isEqualTo(MessageRole.USER));
    }

    @Test
    @DisplayName("나눌 것이 둘 다 비면 Chief의 답이 최종 답이 되고 실행이 하나만 남는다")
    void chiefAnswerBecomesFinalAndOneRunRemainsWhenBothPartsAreEmpty() {
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
    @DisplayName("사용량 목록에 루트 하나만 나오고 월 비용 합계는 넷을 모두 더한다")
    void usageListShowsOnlyRootAndMonthlyCostSumsAllFour() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);
        // 기본값으로 보낸 실행은 세션이 답한 모델로 가격을 찾는다.
        stub().willReportSessionRuntime(new SessionRuntime("example-model-large", "anthropic"));
        ChatTurn turn = chat.send(dad, null, "전기차를 사는 게 나을까?", MY_AGENT);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(dad, null));

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
    @DisplayName("흐름이 없는 에이전트는 지금처럼 실행 하나만 남긴다")
    void agentWithoutFlowLeavesSingleRunAsBefore() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, null);
        stub().willReturn(completed("run-1", "그냥 답"));

        ChatTurn turn = chat.send(dad, null, "안녕", MY_AGENT);

        assertThat(turn.assistantText()).isEqualTo("그냥 답");
        assertThat(executionsOf(dad))
                .singleElement()
                .satisfies(execution -> assertThat(execution.rootExecutionId()).isNull());
    }

    /** 나란히 도는 두 단계가 실제로 함께 떠 있는지 본다. 줄서면 이 흐름의 값이 사라진다. */
    @Test
    @DisplayName("Researcher와 Engineer가 실제로 함께 떠 있다")
    void researcherAndEngineerActuallyRunTogether() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        CountDownLatch bothSubmitted = new CountDownLatch(2);
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
    @DisplayName("Chief가 도는 중에 멈추면 Chief를 멈추고 자식을 시작하지 않는다")
    void stoppingWhileChiefRunsStopsChiefAndStartsNoChild() {
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
        assertThat(executionsOf(dad))
                .singleElement()
                .satisfies(root -> assertThat(root.status()).isEqualTo(ExecutionStatus.CANCELLED));
        ChatEvent started = relayed.stream()
                .filter(event -> "started".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(cancelledEvents(started.executionId())).containsExactly(ExecutionEventType.RUN_CANCELLED);
        assertThat(conversationOf(started.conversationId()).hermesSessionId()).isEqualTo("sess-chief");
        assertThat(relayed.getLast().type()).isEqualTo("stopped");
    }

    @Test
    @DisplayName("Chief가 끝난 뒤 자식 제출 전에 멈추면 자식을 만들지 않는다")
    void stoppingAfterChiefBeforeChildSubmitCreatesNoChild() throws Exception {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        hermesAnswersEachStep(SPLIT_JSON);
        List<ChatEvent> relayed = new ArrayList<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> stop = new AtomicReference<>();
        try {
            chat.stream(dad, null, "전기차를 사는 게 나을까?", MY_AGENT, event -> {
                relayed.add(event);
                if ("step".equals(event.type())
                        && "chief".equals(event.stepName())
                        && "completed".equals(event.stepState())) {
                    Long rootId = relayed.stream()
                            .filter(it -> "started".equals(it.type()))
                            .findFirst()
                            .orElseThrow()
                            .executionId();
                    stop.set(worker.submit(() -> chat.stop(dad, rootId)));
                    awaitCancellation(rootId);
                }
            });

            stop.get().get(1, TimeUnit.SECONDS);
            assertThat(stub().received()).singleElement();
            assertThat(executionsOf(dad))
                    .singleElement()
                    .satisfies(root -> assertThat(root.status()).isEqualTo(ExecutionStatus.CANCELLED));
            assertThat(relayed.getLast().type()).isEqualTo("stopped");
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("자식 runId가 저장된 뒤 trackRun 전에 중지해도 그 자식을 멈춘다")
    void stopsChildEvenIfStoppedAfterRunIdSavedBeforeTrackRun() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        AtomicBoolean intercepted = new AtomicBoolean();
        stub().willAnswer(command -> {
            if (command.input().contains(CHIEF_MARK)) {
                return completed("run-chief", SPLIT_JSON);
            }
            return HermesRunResult.of(
                    "run-child", null, "cancelled", "", "example-model-large", "anthropic", TokenUsage.empty());
        });
        doAnswer(invocation -> {
                    String runId = invocation.getArgument(3);
                    if ("run-child".equals(runId) && intercepted.compareAndSet(false, true)) {
                        Long rootId = executionsOf(dad).stream()
                                .filter(execution -> execution.rootExecutionId() == null)
                                .findFirst()
                                .orElseThrow()
                                .id();
                        chat.stop(dad, rootId);
                    }
                    return invocation.callRealMethod();
                })
                .when(turns)
                .trackRun(any(), any(), any(), any());

        chat.stream(dad, null, "전기차를 사는 게 나을까?", MY_AGENT, event -> {});

        assertThat(intercepted).isTrue();
        assertThat(stub().stopped()).contains("run-child");
    }

    @Test
    @DisplayName("자식 둘이 도는 중에 멈추면 둘을 멈추고 합치기를 시작하지 않는다")
    void stoppingWhileTwoChildrenRunStopsBothAndDoesNotStartMerge() {
        CurrentUser dad = member(MY_EMAIL, MY_AGENT, ResearchAndBuildFlow.NAME);
        List<ChatEvent> relayed = new ArrayList<>();
        CountDownLatch childrenSubmitted = new CountDownLatch(2);
        CountDownLatch childrenAwaiting = new CountDownLatch(2);
        CountDownLatch stopSent = new CountDownLatch(1);
        AtomicInteger awaitCalls = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<Instant> chiefFinishedAt = new AtomicReference<>();
        AtomicReference<Long> chiefLatencyMs = new AtomicReference<>();
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
        // 기본값으로 보낸 실행은 세션이 답한 모델로 가격을 찾는다.
        stub().willReportSessionRuntime(new SessionRuntime("example-model-large", "anthropic"));
        stub().beforeAwait(() -> {
            if (awaitCalls.incrementAndGet() == 1) {
                return;
            }
            childrenAwaiting.countDown();
            await(childrenAwaiting);
            if (stopped.compareAndSet(false, true)) {
                try {
                    ChatEvent started = relayed.stream()
                            .filter(event -> "started".equals(event.type()))
                            .findFirst()
                            .orElseThrow();
                    AgentExecution chief =
                            executions.findById(started.executionId()).orElseThrow();
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
        assertThat(stub().received())
                .hasSize(3)
                .noneMatch(command -> command.input().contains("중간 산출물을 합쳐"));
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
        assertThat(executionsOf(dad))
                .filteredOn(execution -> execution.rootExecutionId() != null)
                .allSatisfy(child ->
                        assertThat(cancelledEvents(child.id())).containsExactly(ExecutionEventType.RUN_CANCELLED));
        assertThat(relayed.getLast().type()).isEqualTo("stopped");
    }

    /** 둘 다 제출될 때까지 기다린다. 줄서면 여기서 끝나지 않고 검사가 실패한다. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 단계가 함께 떠 있지 않다");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void awaitCancellation(Long executionId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (turns.isCancelled(executionId)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("중지 요청이 turn 에 등록되지 않았다");
    }
}
