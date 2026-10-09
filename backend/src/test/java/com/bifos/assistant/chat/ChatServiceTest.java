package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ArtifactService;
import com.bifos.assistant.chat.application.AskFormat;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.application.SkillCommand;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelTierDefinition;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ModelTierDefinitionRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.ToolDetailScope;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.orchestration.application.ResearchAndBuildFlow;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillList;
import com.bifos.assistant.skill.application.SkillListItem;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.application.SkillSource;
import com.bifos.assistant.skill.application.SkillsChanged;
import com.bifos.assistant.skill.domain.ExecutionSkillUse;
import com.bifos.assistant.skill.domain.type.SkillUseSource;
import com.bifos.assistant.skill.infra.ExecutionSkillUseRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.EventObservation;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionContextSourceRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

@BackendIntegrationTest
class ChatServiceTest {

    /** 켜진 스킬 캐시의 보관 시간을 검사가 옮긴다. */
    @Autowired
    TestClock clock;

    @Autowired
    ChatService chat;
    /** 결과물 폴더 단락의 문구는 {@code ArtifactTest} 가 글자 그대로 견준다. 여기서는 그 단락을 받아 쓴다. */
    @Autowired
    ArtifactService artifactService;

    @Autowired
    ChatController controller;

    @Autowired
    ConversationAccess access;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ModelTierDefinitionRepository tierDefinitions;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionContextSourceRepository contextSources;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository memoryRepository;

    @Autowired
    HermesRunsClient hermes;

    /** 실행 사건을 검사하려면 스트림을 우리가 열어 주어야 한다. */
    @Autowired
    HermesRunEventStream eventStream;

    /** 저장이 실패해도 대화가 이어지는지 보려면 저장소가 던지게 만들 수 있어야 한다. */
    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    ExecutionSkillUseRepository skillUses;

    /** 스킬 커맨드가 확인하는 켜진 스킬 목록을 테스트가 정한다. Hermes 대시보드를 부르지 않는다. */
    @Autowired
    SkillService skillService;

    @Autowired
    ApplicationEventPublisher applicationEvents;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    /** 사건 스트림이 {@code skill_view} 시작 사건에 가리기 전 미리보기에서 꺼낸 이름을 실어 보낸 것처럼 만든다. */
    private static RunEvent skillViewStarted(String detail, String skillName) {
        return new RunEvent(
                "tool.started",
                null,
                "skill_view",
                detail,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                skillName);
    }

    /** Hermes 가 스트림으로 이 사건들을 차례로 보낸 것처럼 만든다. */
    private void hermesStreams(RunEvent... events) {
        doAnswer(invocation -> {
                    Consumer<RunEvent> onEvent = invocation.getArgument(3);
                    for (RunEvent event : events) {
                        onEvent.accept(event);
                    }
                    return null;
                })
                .when(eventStream)
                .open(any(), any(), any(), any(), any(), any(ToolDetailScope.class));
    }

    private List<ExecutionEvent> eventsOf(Long executionId) {
        return executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId));
    }

    private static List<ExecutionEventType> typesOf(List<ExecutionEvent> events) {
        return events.stream().map(ExecutionEvent::eventType).toList();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        deleteRows();
    }

    @BeforeEach
    void reset() {
        stub().reset();
        deleteRows();
    }

    private CurrentUser member(String email, String profileName) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, Instant.now()));
        if (profileName != null) {
            agents.save(Agent.of(
                    profileName,
                    profileName,
                    profileName,
                    "http://agent-runtime.test/p/" + profileName,
                    CostMode.SUBSCRIPTION,
                    CredentialScope.SHARED_HOUSEHOLD,
                    AgentVisibility.PRIVATE,
                    user.id(),
                    Instant.now()));
        }
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    @Test
    @DisplayName("깨진 단계 정의로 모델 해석이 실패해도 질문과 FAILED 실행을 남기고 Hermes에는 제출하지 않는다")
    void recordsFailedExecutionWhenStoredTierDefinitionIsMalformed() {
        CurrentUser dad = member("dad@example.com", "dad");
        Agent agent = agents.findByCode("dad").orElseThrow();
        Conversation conversation =
                conversations.save(Conversation.startedBy(dad.id(), "대화", agent.id(), Instant.now()));
        transactions.executeWithoutResult(status -> conversations.chooseModelTierIfActive(
                conversation.id(), dad.id(), ModelSelectionMode.TIER, ModelTier.FAST));
        tierDefinitions.save(ModelTierDefinition.of(dad.groupId(), ModelTier.FAST, null, "example-fast", "low"));

        assertThatThrownBy(() -> chat.send(dad, conversation.id(), "질문", null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).singleElement();
        assertThat(executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(execution -> {
                    assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(execution.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
                    assertThat(eventsOf(execution.id())).singleElement().satisfies(event -> {
                        assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
                        assertThat(event.detail()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
                    });
                });
        assertThat(stub().received()).isEmpty();
        assertThat(chat.running(dad, conversation.id()).running()).isFalse();
    }

    @Test
    @DisplayName("routes the turn to the caller own profile and records what it used")
    void routesTheTurnToTheCallerOwnProfileAndRecordsWhatItUsed() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-1",
                "sess-1",
                "completed",
                "저녁은 김치찌개가 좋겠어요.",
                "example-model-large",
                "anthropic",
                new TokenUsage(120L, 80L, 40L, 160L)));
        stub().willReportSessionRuntime(new SessionRuntime("example-model-large", "anthropic"));
        stub().beforeAwait(() -> assertThat(executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(execution -> {
                    assertThat(execution.status()).isEqualTo(ExecutionStatus.RUNNING);
                    assertThat(execution.hermesRunId()).isEqualTo("run-1");
                }));

        ChatTurn turn = chat.send(dad, null, "오늘 저녁 뭐 먹을까?", "dad");

        assertThat(executions.count()).isOne();

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.profileName()).isEqualTo("dad");
            assertThat(command.apiBaseUrl()).isEqualTo("http://agent-runtime.test/p/dad");
            // 사용자가 쓴 글 앞에 결과물 폴더 단락이 매 turn 붙는다.
            assertThat(command.input())
                    .isEqualTo(
                            artifactService.agentPreamble(conversations
                                            .findById(turn.conversationId())
                                            .orElseThrow()) + "오늘 저녁 뭐 먹을까?");
            // Memory 가 없어도 묻는 형식 안내는 늘 붙는다.
            assertThat(command.instructions()).contains("GFM", "| --- | --- |").endsWith(AskFormat.GUIDE);
            // 새 대화도 Control Plane 이 정한 session 으로 첫 turn 을 보낸다.
            assertThat(command.sessionId()).startsWith("fos-");
        });
        assertThat(turn.assistantText()).isEqualTo("저녁은 김치찌개가 좋겠어요.");

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.userId()).isEqualTo(dad.id());
        assertThat(execution.profileName()).isEqualTo("dad");
        assertThat(execution.provider()).isEqualTo("anthropic");
        assertThat(execution.model()).isEqualTo("example-model-large");
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.inputTokens()).isEqualTo(120);
        assertThat(execution.cachedInputTokens()).isEqualTo(80);
        assertThat(execution.outputTokens()).isEqualTo(40);
        assertThat(execution.totalTokens()).isEqualTo(160);
        assertThat(execution.costMode()).isEqualTo(CostMode.SUBSCRIPTION);
        assertThat(execution.estimatedCostMicros()).isNull();
        assertThat(execution.latencyMs()).isGreaterThanOrEqualTo(0);
        String sentInstructions = stub().received().getFirst().instructions();
        String commonInstructions = sentInstructions.substring(0, sentInstructions.indexOf("\n\n" + AskFormat.GUIDE));
        assertThat(execution.contextChars()).isEqualTo(commonInstructions.length());
        assertThat(execution.instructionsHash())
                .isEqualTo(new AssembledContext(commonInstructions, commonInstructions.length()).instructionsHash());

        assertThat(messages.findByConversationIdOrderByIdAsc(turn.conversationId()))
                .satisfiesExactly(
                        message -> {
                            assertThat(message.role()).isEqualTo(MessageRole.USER);
                            assertThat(message.senderUserId()).isEqualTo(dad.id());
                        },
                        message -> {
                            assertThat(message.role()).isEqualTo(MessageRole.ASSISTANT);
                            assertThat(message.senderUserId()).isNull();
                        });
    }

    @Test
    @DisplayName("조립한 Memory를 Hermes에 보내고 실행 기록에도 길이를 남긴다")
    void sendsAssembledMemoryToHermesAndRecordsLengthInRunRecord() {
        CurrentUser dad = member("dad@example.com", "dad");
        memories.create(dad, MemoryScope.USER, "선호", "국수는 맵지 않게 먹는다", true);
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));

        ChatTurn turn = chat.send(dad, null, "저녁 메뉴", "dad");

        String instructions = stub().received().getFirst().instructions();
        assertThat(instructions).contains("국수는 맵지 않게 먹는다").endsWith("\n\n" + AskFormat.GUIDE);
        // 실행 기록은 공통 지침과 Memory를 세고, turn 전용 지침은 제외한다.
        assertThat(executions.findById(turn.executionId()).orElseThrow().contextChars())
                .isEqualTo((long) (instructions.length() - ("\n\n" + AskFormat.GUIDE).length()));
    }

    @Test
    @DisplayName("turn 에 실은 항상 층과 개인 사실과 색인 항목의 참조를 제목과 본문 없이 실행에 남긴다")
    void recordsAlwaysAndIndexSourcesOfTurnWithoutTitleOrBody() {
        CurrentUser dad = member("dad@example.com", "dad");
        Memory always = memories.create(dad, MemoryScope.USER, "자전거 보관", "자전거는 지하 2층 보관대에 둔다", true);
        Memory fact = memories.create(dad, MemoryScope.USER, "아침 음료", "사실-표식-5208 아침에는 보리차를 마신다", false);
        // 본문이 개인 사실 구역의 상한(200자)을 넘어 색인에만 오른다
        Memory indexed = memories.create(
                dad, MemoryScope.USER, "화분 물 주기", "평문-표식-7391 화분은 열흘마다 물을 준다. " + "물".repeat(200), false);
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));

        ChatTurn turn = chat.send(dad, null, "주말 계획", "dad");

        assertThat(contextSources.findByIdExecutionIdOrderByIdPositionAsc(turn.executionId()))
                .as("실행 %s 에 남은 문맥 참조", turn.executionId())
                .extracting(
                        ExecutionContextSource::position,
                        ExecutionContextSource::source,
                        ExecutionContextSource::sourceRef,
                        ExecutionContextSource::bodyMode)
                .containsExactly(
                        tuple(0, "MEMORY_ALWAYS", "memory:" + always.id(), "INLINE"),
                        tuple(1, "MEMORY_FACTS", "memory:" + fact.id(), "INLINE"),
                        tuple(2, "MEMORY_INDEX", "memory:" + indexed.id(), "TITLE_ONLY"));
        // 표의 모든 칸을 읽어 어느 칸에도 제목과 본문이 없는지 본다.
        List<Map<String, Object>> rows =
                jdbc.queryForList("select * from execution_context_source where execution_id = ?", turn.executionId());
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().flatMap(row -> row.values().stream()).map(String::valueOf))
                .as("실행 %s 의 문맥 참조 줄의 칸 값", turn.executionId())
                .noneSatisfy(value -> assertThat(value)
                        .containsAnyOf("자전거 보관", "자전거는 지하 2층 보관대에 둔다", "아침 음료", "사실-표식-5208", "화분 물 주기", "평문-표식-7391"));
    }

    @Test
    @DisplayName("커넥터 대화는 Memory 없이 공통 표 지침을 보내고 길이와 해시를 기록한다")
    void sendsNoMemoryToHermesForConnectorAgentTurn() {
        CurrentUser dad = member("dad@example.com", "dad");
        Agent connector = agents.findAll().getFirst();
        connector.markConnectorManaged();
        agents.save(connector);
        memories.create(dad, MemoryScope.USER, "선호", "국수는 맵지 않게 먹는다", true);
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));

        ChatTurn turn = chat.send(dad, null, "저녁 메뉴", "dad");

        String instructions = stub().received().getFirst().instructions();
        assertThat(instructions)
                .as("커넥터 에이전트의 turn 이 Hermes 에 보낸 instructions")
                .doesNotContain("국수는 맵지 않게 먹는다")
                .contains("GFM", "구분 줄")
                .endsWith("\n\n" + AskFormat.GUIDE);
        String commonInstructions = instructions.substring(0, instructions.indexOf("\n\n" + AskFormat.GUIDE));
        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.contextChars()).isEqualTo(commonInstructions.length());
        assertThat(execution.instructionsHash())
                .isEqualTo(new AssembledContext(commonInstructions, commonInstructions.length()).instructionsHash());
    }

    @Test
    @DisplayName("일반 에이전트의 turn 은 붙은 커넥터 서버의 도구만 가리는 범위로 사건 스트림을 연다")
    void opensEventStreamHidingOnlyAttachedConnectorTools() {
        CurrentUser dad = member("dad@example.com", "dad");
        Agent agent = agents.findByCode("dad").orElseThrow();
        ConnectorConnection connection =
                connections.save(ConnectorConnection.pending(dad.id(), "demo-notes", Instant.now()));
        bindings.save(ConnectorBinding.pending(agent, connection, "demo", Instant.now()));
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(new RunEvent("run.completed", null, null, null, null, null));

        chat.stream(dad, null, "메모 찾아 줘", "dad", event -> {});

        verify(eventStream, timeout(5000))
                .open(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        argThat(scope ->
                                !scope.hideAll() && scope.hiddenPrefixes().equals(Set.of("mcp__demo__"))));
    }

    @Test
    @DisplayName("한 번에 받는 경로도 붙은 커넥터 서버의 도구 사건을 실행 기록에 남기되 첫 조각 시각은 비운다")
    void sendRecordsBoundConnectorToolEventsWithoutFirstDelta() {
        CurrentUser dad = member("dad@example.com", "dad");
        Agent agent = agents.findByCode("dad").orElseThrow();
        ConnectorConnection connection =
                connections.save(ConnectorConnection.pending(dad.id(), "demo-notes", Instant.now()));
        bindings.save(ConnectorBinding.pending(agent, connection, "demo", Instant.now()));
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        // 사건 스트림이 붙은 서버의 도구 내용을 이미 가린 채로 넘기는 모양이다. 가리는 판정은 스트림 쪽 시험이 본다.
        hermesStreams(
                new RunEvent("message.delta", "조각", null, null, null, null),
                new RunEvent("tool.started", null, "mcp__demo__list_notes", "[연결 도구 내용 가림]", null, null),
                new RunEvent("tool.completed", null, "mcp__demo__list_notes", "[연결 도구 내용 가림]", 50L, false),
                new RunEvent("run.completed", null, null, null, null, null));

        ChatTurn turn = chat.send(dad, null, "메모 찾아 줘", "dad");

        verify(eventStream)
                .open(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        argThat(scope ->
                                !scope.hideAll() && scope.hiddenPrefixes().equals(Set.of("mcp__demo__"))));
        List<ExecutionEvent> recorded = eventsOf(turn.executionId());
        assertThat(typesOf(recorded))
                .containsExactly(
                        ExecutionEventType.RUN_STARTED,
                        ExecutionEventType.TOOL_STARTED,
                        ExecutionEventType.TOOL_COMPLETED,
                        ExecutionEventType.RUN_COMPLETED);
        assertThat(recorded).extracting(ExecutionEvent::sequence).containsExactly(1, 2, 3, 4);
        assertThat(recorded.get(1).toolName()).isEqualTo("mcp__demo__list_notes");
        assertThat(recorded.get(2).durationMs()).isEqualTo(50L);
        assertThat(recorded.get(2).failed()).isFalse();
        assertThat(recorded).extracting(ExecutionEvent::detail).containsSequence("[연결 도구 내용 가림]", "[연결 도구 내용 가림]");
        assertThat(executions.findById(turn.executionId()).orElseThrow().firstDeltaAt())
                .as("한 번에 받는 경로는 첫 반응 시간 통계에 들지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("한 번에 받는 경로에서 사건 스트림이 닫히지 않아도 실행 한도 뒤에는 결과 조회로 넘어가 답을 남긴다")
    void sendMovesOnToResultLookupWhenEventStreamStaysOpen() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        CountDownLatch neverClosed = new CountDownLatch(1);
        doAnswer(invocation -> {
                    neverClosed.await(30, TimeUnit.SECONDS);
                    return null;
                })
                .when(eventStream)
                .open(any(), any(), any(), any(), any(), any(ToolDetailScope.class));
        try {
            long startedAt = System.nanoTime();

            ChatTurn turn = chat.send(dad, null, "안녕", "dad");

            // 시험 설정의 hermes.run-timeout 은 1초다. 스트림을 끝까지 기다렸다면 30초가 걸린다.
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(10));
            assertThat(turn.assistantText()).isEqualTo("네");
            assertThat(executions.findById(turn.executionId()).orElseThrow().eventObservation())
                    .isEqualTo(EventObservation.INCOMPLETE);
        } finally {
            neverClosed.countDown();
        }
    }

    @Test
    @DisplayName("한 번에 받는 경로에서 사건 스트림을 읽지 못해도 답은 남고 시작과 끝 사건은 남는다")
    void sendSucceedsWhenEventStreamCannotBeRead() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "stream down"))
                .when(eventStream)
                .open(any(), any(), any(), any(), any(), any(ToolDetailScope.class));

        ChatTurn turn = chat.send(dad, null, "안녕", "dad");

        assertThat(turn.assistantText()).isEqualTo("네");
        assertThat(executions.findById(turn.executionId()).orElseThrow().eventObservation())
                .isEqualTo(EventObservation.INCOMPLETE);
        assertThat(typesOf(eventsOf(turn.executionId())))
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    @DisplayName("옛 커넥터 에이전트의 turn 은 모든 도구를 가리는 범위로 사건 스트림을 연다")
    void opensEventStreamHidingAllToolsForLegacyConnectorAgent() {
        CurrentUser dad = member("dad@example.com", "dad");
        Agent legacy = agents.findByCode("dad").orElseThrow();
        legacy.markConnectorManaged();
        agents.save(legacy);
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(new RunEvent("run.completed", null, null, null, null, null));

        chat.stream(dad, null, "메모 찾아 줘", "dad", event -> {});

        verify(eventStream, timeout(5000)).open(any(), any(), any(), any(), any(), argThat(ToolDetailScope::hideAll));
    }

    @Test
    @DisplayName("message history includes the user display name only on user messages")
    void messageHistoryIncludesTheUserDisplayNameOnlyOnUserMessages() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "반가워요", "m", "p", TokenUsage.empty()));
        ChatTurn turn = chat.send(dad, null, "안녕", "dad");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(dad, null));

        assertThat(controller.messages(turn.conversationPublicId()))
                .satisfiesExactly(
                        message -> {
                            assertThat(message.role()).isEqualTo("USER");
                            assertThat(message.senderName()).isEqualTo("dad@example.com");
                        },
                        message -> {
                            assertThat(message.role()).isEqualTo("ASSISTANT");
                            assertThat(message.senderName()).isNull();
                        });
    }

    /**
     * 압축 교체로 Hermes 가 보낸 것과 다른 session 을 돌려준 경우다.
     *
     * <p>다음 turn 은 돌려받은 session 을 보내지만, 대화의 루트와 실행 줄에는 처음 정한 session 이 남는다.
     */
    @Test
    @DisplayName("continues the same hermes session on the next turn")
    void continuesTheSameHermesSessionOnTheNextTurn() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn first = chat.send(dad, null, "안녕", "dad");

        stub().willReturn(HermesRunResult.of("run-2", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn second = chat.send(dad, first.conversationId(), "하나 더", "mom");

        String root = stub().received().get(0).sessionId();
        assertThat(root).startsWith("fos-");
        assertThat(stub().received().get(1).sessionId()).isEqualTo("sess-1");
        var conversation = conversations.findById(first.conversationId()).orElseThrow();
        assertThat(conversation.hermesSessionId()).as("다음에 보낼 session").isEqualTo("sess-1");
        assertThat(conversation.hermesRootSessionId()).as("루트 session").isEqualTo(root);
        assertThat(executions.findById(second.executionId()).orElseThrow().hermesSessionId())
                .as("둘째 실행 줄의 session")
                .isEqualTo(root);
    }

    @Test
    @DisplayName("사용자가 질문을 보내면 질문 없이 연 turn 의 수가 0 이 된다")
    void resetsAutoTurnCountWhenUserSendsQuestion() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn first = chat.send(dad, null, "안녕", "dad");
        jdbc.update("UPDATE conversation SET auto_turn_count = 3 WHERE id = ?", first.conversationId());
        assertThat(conversations.findById(first.conversationId()).orElseThrow().autoTurnCount())
                .isEqualTo(3);

        stub().willReturn(HermesRunResult.of("run-2", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        chat.send(dad, first.conversationId(), "하나 더", "dad");

        assertThat(conversations.findById(first.conversationId()).orElseThrow().autoTurnCount())
                .isZero();
    }

    @Test
    @DisplayName("목록에서 숨긴 대화에 사용자가 질문을 보내면 숨김이 풀려 다시 목록에 보인다")
    void showsHiddenConversationAgainWhenUserSendsQuestion() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn first = chat.send(dad, null, "안녕", "dad");
        assertThat(conversationWriter.hideFromList(first.conversationId(), Instant.parse("2026-11-01T00:00:00Z")))
                .as("숨긴 대화 수")
                .isEqualTo(1);
        assertThat(chat.conversationsOf(dad, null, 10).items())
                .extracting(Conversation::id)
                .as("숨긴 뒤의 목록")
                .doesNotContain(first.conversationId());

        stub().willReturn(HermesRunResult.of("run-2", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        chat.send(dad, first.conversationId(), "하나 더", "dad");

        assertThat(conversations.findById(first.conversationId()).orElseThrow().hiddenAt())
                .as("질문 뒤의 숨긴 시각")
                .isNull();
        assertThat(chat.conversationsOf(dad, null, 10).items())
                .extracting(Conversation::id)
                .as("질문 뒤의 목록")
                .contains(first.conversationId());
    }

    /** Hermes 가 받은 session 을 그대로 돌려주게 한다. 실제 Hermes 가 모르는 id 를 받았을 때와 같다. */
    private void hermesEchoesSession() {
        stub().willAnswer(command -> HermesRunResult.of(
                "run-" + stub().received().size(),
                command.sessionId(),
                "completed",
                "네",
                "m",
                "p",
                TokenUsage.empty()));
    }

    @Test
    @DisplayName("새 대화의 첫 turn은 정한 session을 보내고 대화의 두 칸과 실행 줄에 제출 전에 적는다")
    void firstTurnSendsChosenSessionAndRecordsItBeforeSubmit() {
        CurrentUser dad = member("dad@example.com", "dad");
        AtomicReference<String> recordedAtSubmit = new AtomicReference<>();
        stub().willAnswer(command -> {
            // 제출하는 순간 이미 실행 줄에 session 이 적혀 있어야 한다.
            recordedAtSubmit.set(executions
                    .findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10))
                    .getFirst()
                    .hermesSessionId());
            return HermesRunResult.of("run-1", command.sessionId(), "completed", "네", "m", "p", TokenUsage.empty());
        });

        ChatTurn turn = chat.send(dad, null, "안녕", "dad");

        String sent = stub().received().getFirst().sessionId();
        assertThat(sent).startsWith("fos-");
        assertThat(recordedAtSubmit.get()).as("제출할 때 실행 줄의 session").isEqualTo(sent);
        var conversation = conversations.findById(turn.conversationId()).orElseThrow();
        assertThat(conversation.hermesSessionId()).isEqualTo(sent);
        assertThat(conversation.hermesRootSessionId()).isEqualTo(sent);
        assertThat(executions.findById(turn.executionId()).orElseThrow().hermesSessionId())
                .isEqualTo(sent);
    }

    @Test
    @DisplayName("둘째 turn은 같은 session을 보내고 새로 만들지 않는다")
    void secondTurnSendsSameSessionWithoutCreatingNew() {
        CurrentUser dad = member("dad@example.com", "dad");
        hermesEchoesSession();

        ChatTurn first = chat.send(dad, null, "안녕", "dad");
        ChatTurn second = chat.send(dad, first.conversationId(), "하나 더", "dad");

        String sent = stub().received().get(0).sessionId();
        assertThat(stub().received().get(1).sessionId()).isEqualTo(sent);
        assertThat(conversations.findById(first.conversationId()).orElseThrow().hermesRootSessionId())
                .isEqualTo(sent);
        assertThat(executions.findById(second.executionId()).orElseThrow().hermesSessionId())
                .isEqualTo(sent);
    }

    @Test
    @DisplayName("루트가 없는 옛 대화는 Hermes가 정한 session을 보내고 실행 줄에도 그 값을 적는다")
    void oldConversationWithoutRootSendsSessionChosenByHermesAndRecordsIt() {
        CurrentUser dad = member("dad@example.com", "dad");
        Long agentId = agents.findByCode("dad").orElseThrow().id();
        Conversation legacy = conversations.save(Conversation.startedBy(dad.id(), "옛 대화", agentId, Instant.now()));
        conversationWriter.touchSession(legacy.id(), "hermes-made-session", Instant.now());
        hermesEchoesSession();

        ChatTurn turn = chat.send(dad, legacy.id(), "이어서", "dad");

        assertThat(stub().received().getFirst().sessionId()).isEqualTo("hermes-made-session");
        assertThat(executions.findById(turn.executionId()).orElseThrow().hermesSessionId())
                .isEqualTo("hermes-made-session");
        assertThat(conversations.findById(legacy.id()).orElseThrow().hermesRootSessionId())
                .isNull();
    }

    @Test
    @DisplayName("기본값으로 보냈고 세션이 답하지 못하면 provider와 모델이 비어 있다")
    void providerAndModelAreEmptyWhenSentWithDefaultAndSessionCannotAnswer() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));

        ChatTurn turn = chat.send(dad, null, "안녕", "dad");

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.model()).as("model").isNull();
        assertThat(execution.provider()).as("provider").isNull();
    }

    @Test
    @DisplayName("refuses a member with no profile bound and never calls the runtime")
    void refusesAMemberWithNoProfileBoundAndNeverCallsTheRuntime() {
        CurrentUser kid = member("kid@example.com", null);

        assertThatThrownBy(() -> chat.send(kid, null, "숙제 도와줘", "missing"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_NOT_FOUND);
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("refuses to read another member conversation")
    void refusesToReadAnotherMemberConversation() {
        CurrentUser dad = member("dad@example.com", "dad");
        CurrentUser mom = member("mom@example.com", "mom");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn dadTurn = chat.send(dad, null, "비밀 얘기", "dad");

        assertThatThrownBy(() -> chat.history(mom, dadTurn.conversationId()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("records a failed run so the usage view still shows it")
    void recordsAFailedRunSoTheUsageViewStillShowsIt() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", "dad")).isInstanceOf(ApiException.class);

        var recorded = executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10));
        assertThat(recorded).singleElement().satisfies(execution -> {
            assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(execution.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        });
    }

    @Test
    @DisplayName("Hermes가 실패 결과를 돌려줘도 실행 줄 하나를 FAILED로 갱신한다")
    void updatesSingleRunRowToFailedWhenHermesReturnsFailure() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "failed", null, "dad", null, TokenUsage.empty()));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", "dad"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_RUN_FAILED);

        assertThat(executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(execution -> {
                    assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(execution.hermesRunId()).isEqualTo("run-1");
                    assertThat(execution.errorCode()).isEqualTo("FAILED");
                });
    }

    /**
     * 모델이 {@code skill_view} 로 스킬을 읽으면 그 실행에 {@code MODEL} 호출 이력이 남는다. 같은 스킬을 참고
     * 파일까지 두 번 읽어도 한 줄이다. 도구 사건 자체는 다른 도구와 같이 남는다.
     */
    @Test
    @DisplayName("스트림에서 skill view 도구 사건이 오면 그 실행에 MODEL 이력이 하나 생긴다")
    void skillViewToolEventInStreamCreatesOneModelHistoryForRun() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(
                        HermesRunResult.of("run-1", "sess-1", "completed", "장을 봤어요", "dad", null, TokenUsage.empty()));
        hermesStreams(
                skillViewStarted("shopping", "shopping"),
                new RunEvent("tool.completed", null, "skill_view", null, 50L, false),
                skillViewStarted("shopping → references/list.md", "shopping"),
                new RunEvent("tool.completed", null, "skill_view", null, 50L, false),
                new RunEvent("run.completed", null, null, null, null, null));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "장보기 스킬을 써 줘", "dad", relayed::add);

        Long executionId = relayed.getLast().executionId();
        assertThat(skillUses.findByExecutionIdInOrderByExecutionIdAscSkillNameAsc(List.of(executionId)))
                .singleElement()
                .satisfies(use -> {
                    assertThat(use.skillName()).isEqualTo("shopping");
                    assertThat(use.source()).isEqualTo(SkillUseSource.MODEL);
                });
        assertThat(typesOf(eventsOf(executionId)))
                .containsExactly(
                        ExecutionEventType.RUN_STARTED,
                        ExecutionEventType.TOOL_STARTED,
                        ExecutionEventType.TOOL_COMPLETED,
                        ExecutionEventType.TOOL_STARTED,
                        ExecutionEventType.TOOL_COMPLETED,
                        ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    @DisplayName("스트리밍 한 번이 RUN STARTED로 시작해 RUN COMPLETED로 끝나는 사건을 남긴다")
    void oneStreamLeavesEventsFromRunStartedToRunCompleted() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent("message.delta", "조각", null, null, null, null),
                new RunEvent("tool.started", null, "web_search", "started", null, null),
                new RunEvent("tool.completed", null, "web_search", null, 1500L, false),
                new RunEvent("subagent.start", null, "researcher", "찾는다", null, null),
                new RunEvent("subagent.complete", null, "researcher", "찾았다", 2000L, false),
                new RunEvent("run.completed", null, null, null, null, null));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "도구를 써 줘", "dad", relayed::add);

        Long executionId = relayed.getLast().executionId();
        List<ExecutionEvent> recorded = eventsOf(executionId);
        assertThat(typesOf(recorded))
                .containsExactly(
                        ExecutionEventType.RUN_STARTED,
                        ExecutionEventType.TOOL_STARTED,
                        ExecutionEventType.TOOL_COMPLETED,
                        ExecutionEventType.SUBAGENT_STARTED,
                        ExecutionEventType.SUBAGENT_COMPLETED,
                        ExecutionEventType.RUN_COMPLETED);
        assertThat(recorded).extracting(ExecutionEvent::sequence).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(recorded.get(2).toolName()).isEqualTo("web_search");
        assertThat(recorded.get(2).durationMs()).isEqualTo(1500L);
        assertThat(recorded.get(3).subagentName()).isEqualTo("researcher");
        assertThat(recorded.get(3).toolName()).isNull();
        assertThat(relayed.stream().filter(it -> "tool".equals(it.type())).toList())
                .extracting(ChatEvent::phase)
                .containsExactly("started", "completed");
        assertThat(relayed.stream().filter(it -> "subagent".equals(it.type())).toList())
                .extracting(ChatEvent::phase)
                .containsExactly("started", "completed");
        var summary = chat.activitySummaries(chat.history(
                        dad, access.requireOwnId(dad, relayed.getLast().conversationId())))
                .get(executionId);
        assertThat(summary.toolCount()).isEqualTo(1);
        assertThat(summary.subagentCount()).isEqualTo(1);
        assertThat(summary.durationMs()).isNotNull();
    }

    @Test
    @DisplayName("원본 도구의 실패가 대화 사건에도 실패로 전달된다")
    void attachmentInspectionFailureIsRelayedAsFailure() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-1", "sess-1", "completed", "사진을 판독하지 못했다", "dad", null, TokenUsage.empty()));
        hermesStreams(new RunEvent("tool.completed", null, "attachment_inspect", "원본 조회 실패", 500L, true));
        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "사진의 작은 글자는?", "dad", relayed::add);
        assertThat(relayed.stream().filter(event -> "tool".equals(event.type())).toList())
                .extracting(ChatEvent::failed)
                .containsExactly(true);
    }

    @Test
    @DisplayName("사건이 없는 자손은 작업 과정에 세지 않는다")
    void descendantWithoutEventsDoesNotCountAsWorkProcess() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        ChatTurn turn = chat.send(dad, null, "안녕", "dad");
        AgentExecution root = executions.findById(turn.executionId()).orElseThrow();
        executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(turn.conversationId())
                .agentId(root.agentId())
                .parentExecutionId(root.id())
                .rootExecutionId(root.id())
                .profileName(root.profileName())
                .costMode(root.costMode())
                .status(ExecutionStatus.RUNNING)
                .startedAt(root.startedAt())
                .build());

        assertThat(chat.activitySummaries(chat.history(dad, turn.conversationId())))
                .isEmpty();
    }

    /**
     * Hermes 도 실행이 끝났다는 사건을 보내므로 두 줄이 되기 쉽다. 이 검사가 그것을 막는다. 실행의 끝을
     * 적는 자리는 {@code ChatService} 하나여야 한다.
     */
    @Test
    @DisplayName("스트리밍 한 번이 RUN COMPLETED를 한 줄만 남긴다")
    void oneStreamLeavesExactlyOneRunCompletedRow() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent("tool.started", null, "web_search", "started", null, null),
                new RunEvent("run.completed", null, null, null, null, null));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "안녕", "dad", relayed::add);

        assertThat(typesOf(eventsOf(relayed.getLast().executionId())))
                .filteredOn(ExecutionEventType.RUN_COMPLETED::equals)
                .hasSize(1);
    }

    @Test
    @DisplayName("글자 조각은 사건으로 저장하지 않는다")
    void doesNotStoreTextChunksAsEvents() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent("message.delta", "조각 하나", null, null, null, null),
                new RunEvent("message.delta", "조각 둘", null, null, null, null));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "안녕", "dad", relayed::add);

        assertThat(relayed).filteredOn(event -> event.type().equals("delta")).hasSize(2);
        assertThat(typesOf(eventsOf(relayed.getLast().executionId())))
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    @DisplayName("자식 시작 사건만 저장하지 못해도 답은 남고 관측 누락 표시가 정상 종료 뒤에도 남는다")
    void keepsObservationGapWhenOnlyChildStartCannotBeSaved() {
        CurrentUser user = member("observer@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent(
                        "subagent.start",
                        null,
                        null,
                        "도우미 시작",
                        null,
                        null,
                        "child-1",
                        null,
                        null,
                        "child-session",
                        null,
                        null,
                        null,
                        null),
                new RunEvent("run.completed", null, null, null, null, null));
        doThrow(new DataIntegrityViolationException("시작 사건 저장 실패"))
                .when(executionEvents)
                .save(argThat(event -> event.eventType() == ExecutionEventType.SUBAGENT_STARTED));

        ChatTurn turn = chat.send(user, null, "안녕", "dad");

        assertThat(turn.assistantText()).isEqualTo("네");
        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.eventObservation()).isEqualTo(EventObservation.INCOMPLETE);
        assertThat(typesOf(eventsOf(turn.executionId())))
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    @DisplayName("사건 저장이 예외를 던져도 대화는 성공하고 중계도 이어진다")
    void conversationSucceedsAndRelayContinuesEvenIfEventSaveThrows() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-1", "sess-1", "completed", "저녁은 김치찌개", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent("message.delta", "저녁은 ", null, null, null, null),
                new RunEvent("tool.started", null, "web_search", "started", null, null));
        doThrow(new DataIntegrityViolationException("사건을 저장할 수 없다"))
                .when(executionEvents)
                .save(any(ExecutionEvent.class));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "오늘 저녁 뭐 먹을까?", "dad", relayed::add);

        ChatEvent done = relayed.getLast();
        assertThat(done.type()).isEqualTo("done");
        assertThat(relayed).extracting(ChatEvent::type).containsExactly("started", "delta", "tool", "done");
        assertThat(executions.findById(done.executionId()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(messages.findByConversationIdOrderByIdAsc(access.requireOwnId(dad, done.conversationId())))
                .last()
                .satisfies(message -> assertThat(message.content()).isEqualTo("저녁은 김치찌개"));
    }

    @Test
    @DisplayName("종료 사건 없이 스트림이 정상 EOF 로 닫혀도 답은 남고 관측 누락으로 기록한다")
    void keepsObservationGapWhenStreamClosesBeforeTerminalEvent() {
        CurrentUser user = member("eof@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(new RunEvent("message.delta", "네", null, null, null, null));

        ChatTurn turn = chat.send(user, null, "안녕", "dad");

        assertThat(turn.assistantText()).isEqualTo("네");
        AgentExecution saved = executions.findById(turn.executionId()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(saved.eventObservation()).isEqualTo(EventObservation.INCOMPLETE);
    }

    @Test
    @DisplayName("중단 종료 사건까지 정상적으로 읽으면 관측 완료로 기록한다")
    void interruptedTerminalEventCompletesObservation() {
        CurrentUser user = member("interrupted@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(new RunEvent("run.interrupted", null, null, null, null, null));

        ChatTurn turn = chat.send(user, null, "안녕", "dad");

        assertThat(executions.findById(turn.executionId()).orElseThrow().eventObservation())
                .isEqualTo(EventObservation.OBSERVED);
    }

    @Test
    @DisplayName("한 번에 받는 경로로 돈 실행이 도구를 부르지 않았으면 RUN STARTED와 RUN COMPLETED 둘만 남긴다")
    void nonStreamPathWithoutToolCallsLeavesOnlyRunStartedAndRunCompleted() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(new RunEvent("run.completed", null, null, null, null, null));

        ChatTurn turn = chat.send(dad, null, "안녕", "dad");

        List<ExecutionEvent> recorded = eventsOf(turn.executionId());
        assertThat(typesOf(recorded)).containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.RUN_COMPLETED);
        assertThat(recorded).extracting(ExecutionEvent::sequence).containsExactly(1, 2);
        assertThat(executions.findById(turn.executionId()).orElseThrow().eventObservation())
                .isEqualTo(EventObservation.OBSERVED);
    }

    @Test
    @DisplayName("넘어간 모델의 라벨은 ADMIN 역할에게만 주고 MEMBER 역할에게는 빈 묶음을 준다")
    void switchedLabelsGoOnlyToAdmin() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        ChatTurn turn = chat.send(dad, null, "안녕", "dad");
        executionEvents.save(ExecutionEvent.builder()
                .executionId(turn.executionId())
                .sequence(99)
                .eventType(ExecutionEventType.PROVIDER_SWITCHED)
                .detail("example-provider/example-model-small")
                .occurredAt(Instant.now())
                .build());
        List<ChatMessage> history = chat.history(dad, turn.conversationId());
        CurrentUser asAdmin = new CurrentUser(dad.id(), dad.email(), dad.displayName(), dad.groupId(), UserRole.ADMIN);

        assertThat(chat.switchedLabels(dad, history)).isEmpty();
        assertThat(chat.switchedLabels(asAdmin, history))
                .containsExactly(Map.entry(turn.executionId(), "example-provider/example-model-small"));
    }

    @Test
    @DisplayName("실행이 없는 대화의 넘어간 모델 라벨은 ADMIN 역할에게도 빈 묶음이다")
    void switchedLabelsOfHistoryWithoutExecutionsAreEmptyForAdmin() {
        CurrentUser asAdmin = new CurrentUser(1L, "admin@example.com", "admin", 1L, UserRole.ADMIN);

        assertThat(chat.switchedLabels(asAdmin, List.of())).isEmpty();
    }

    @Test
    @DisplayName("막히면 넘기지 않고 PROVIDER BLOCKED 로 실패한다")
    void blockedFailsAsProviderBlockedWithoutFallback() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(
                        new HermesRunResult(
                                "run-blocked",
                                "sess-1",
                                "failed",
                                null,
                                null,
                                null,
                                HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " no account",
                                TokenUsage.empty()),
                        HermesRunResult.of("run-answer", "sess-1", "completed", "답", null, null, TokenUsage.empty()));

        List<ChatEvent> relayed = new ArrayList<>();
        assertThatThrownBy(() -> chat.stream(dad, null, "찾아 줘", "dad", relayed::add))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PROVIDER_BLOCKED);

        assertThat(stub().received()).as("Hermes 를 부른 횟수").hasSize(1);
        assertThat(relayed).extracting(ChatEvent::type).doesNotContain("switched", "reset");
        assertThat(executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(execution -> {
                    assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(execution.errorCode()).isEqualTo("PROVIDER_BLOCKED");
                });
    }

    @Test
    @DisplayName("Hermes가 실패로 끝나면 RUN FAILED가 남고 예외는 그대로 올라간다")
    void leavesRunFailedAndRethrowsWhenHermesEndsInFailure() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "failed", null, "dad", null, TokenUsage.empty()));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", "dad"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_RUN_FAILED);

        Long executionId = executions
                .findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10))
                .getFirst()
                .id();
        assertThat(eventsOf(executionId))
                .satisfiesExactly(
                        started -> assertThat(started.eventType()).isEqualTo(ExecutionEventType.RUN_STARTED),
                        failed -> {
                            assertThat(failed.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
                            assertThat(failed.detail()).isEqualTo("FAILED");
                            assertThat(failed.sequence()).isEqualTo(2);
                        });
    }

    @Test
    @DisplayName("제출이 실패하면 RUN FAILED 하나만 남는다")
    void leavesOnlyOneRunFailedWhenSubmitFails() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", "dad")).isInstanceOf(ApiException.class);

        Long executionId = executions
                .findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10))
                .getFirst()
                .id();
        assertThat(eventsOf(executionId)).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
            assertThat(event.detail()).isEqualTo("HERMES_UNAVAILABLE");
            assertThat(event.sequence()).isEqualTo(1);
        });
    }

    /** 그 에이전트의 스킬 목록을 이렇게 답하게 한다. 이름 뒤에 {@code :off} 를 붙이면 꺼진 스킬이다. */
    private void skillsOf(String agentCode, boolean skillsToolsetEnabled, String... names) {
        List<SkillListItem> items = Arrays.stream(names)
                .map(name -> name.endsWith(":off")
                        ? new SkillListItem(name.substring(0, name.length() - 4), "", SkillSource.UPLOADED, false, null)
                        : new SkillListItem(name, "", SkillSource.UPLOADED, true, null))
                .toList();
        doReturn(new SkillList(items, false, skillsToolsetEnabled, 30))
                .when(skillService)
                .commandList(argThat(agent -> agent != null && agentCode.equals(agent.code())));
    }

    private List<ExecutionSkillUse> skillUsesOf(Long executionId) {
        return skillUses.findByExecutionIdInOrderByExecutionIdAscSkillNameAsc(List.of(executionId));
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }

    private static HermesRunResult answered(String runId, String output) {
        return HermesRunResult.of(runId, "sess-1", "completed", output, "dad", null, TokenUsage.empty());
    }

    @Test
    @DisplayName("켜진 스킬의 커맨드는 Hermes 입력만 바꾸고 메시지는 원문으로 저장하며 COMMAND 이력을 남긴다")
    void enabledSkillCommandChangesOnlyHermesInputAndRecordsCommandHistory() {
        CurrentUser dad = member("dad@example.com", "dad");
        skillsOf("dad", true, "shopping", "cooking:off");
        stub().willReturn(answered("run-1", "장보기 목록이에요"));

        ChatTurn turn = chat.send(dad, null, "/shopping 이번 주", "dad");

        Conversation conversation =
                conversations.findById(turn.conversationId()).orElseThrow();
        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.input())
                    .isEqualTo(artifactService.agentPreamble(conversation)
                            + new SkillCommand("shopping", "이번 주").hermesInput())
                    .contains("skill_view(name=\"shopping\")", "이번 주")
                    .doesNotContain("/shopping");
        });
        assertThat(conversation.title()).as("대화 제목").isEqualTo("/shopping 이번 주");
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .first()
                .satisfies(message -> {
                    assertThat(message.role()).isEqualTo(MessageRole.USER);
                    assertThat(message.content()).isEqualTo("/shopping 이번 주");
                });
        assertThat(skillUsesOf(turn.executionId())).singleElement().satisfies(use -> {
            assertThat(use.skillName()).isEqualTo("shopping");
            assertThat(use.source()).isEqualTo(SkillUseSource.COMMAND);
        });
    }

    @Test
    @DisplayName("켜진 스킬이 아닌 커맨드는 SKILL COMMAND UNKNOWN 이고 대화도 메시지도 실행도 만들지 않는다")
    void commandOfDisabledSkillIsSkillCommandUnknownAndCreatesNothing() {
        CurrentUser dad = member("dad@example.com", "dad");
        skillsOf("dad", true, "shopping", "cooking:off");
        stub().willReturn(answered("run-1", "네"));

        assertCode(() -> chat.send(dad, null, "/nope 해 줘", "dad"), ErrorCode.SKILL_COMMAND_UNKNOWN);
        assertCode(() -> chat.send(dad, null, "/cooking", "dad"), ErrorCode.SKILL_COMMAND_UNKNOWN);
        List<ChatEvent> relayed = new ArrayList<>();
        assertCode(() -> chat.stream(dad, null, "/nope 해 줘", "dad", relayed::add), ErrorCode.SKILL_COMMAND_UNKNOWN);

        assertThat(chat.conversationsOf(dad, null, 100).items()).as("대화").isEmpty();
        assertThat(messages.count()).as("메시지 수").isZero();
        assertThat(executions.count()).as("실행 수").isZero();
        assertThat(stub().received()).as("Hermes 에 보낸 것").isEmpty();
        assertThat(relayed).as("스트림 사건").isEmpty();
    }

    @Test
    @DisplayName("이어 쓰는 대화에서도 없는 이름이면 거절하고 메시지를 더하지 않는다")
    void unknownNameInContinuedConversationIsRejectedWithoutAddingMessage() {
        CurrentUser dad = member("dad@example.com", "dad");
        skillsOf("dad", true, "shopping");
        stub().willReturn(answered("run-1", "네"));
        ChatTurn first = chat.send(dad, null, "안녕", "dad");

        assertCode(() -> chat.send(dad, first.conversationId(), "/nope", null), ErrorCode.SKILL_COMMAND_UNKNOWN);

        assertThat(messages.findByConversationIdOrderByIdAsc(first.conversationId()))
                .as("메시지")
                .hasSize(2);
        assertThat(executions.count()).as("실행 수").isOne();
    }

    @Test
    @DisplayName("skills toolset 이 꺼진 에이전트는 켜진 스킬이어도 SKILL COMMAND UNKNOWN 이다")
    void skillsToolsetOffGivesSkillCommandUnknownEvenForEnabledSkill() {
        CurrentUser dad = member("dad@example.com", "dad");
        skillsOf("dad", false, "shopping");
        stub().willReturn(answered("run-1", "네"));

        assertCode(() -> chat.send(dad, null, "/shopping 이번 주", "dad"), ErrorCode.SKILL_COMMAND_UNKNOWN);

        assertThat(stub().received()).isEmpty();
        assertThat(executions.count()).isZero();
    }

    @Test
    @DisplayName("흐름이 붙은 에이전트는 커맨드를 해석하지 않고 글 그대로 보낸다")
    void flowAgentSendsTextAsIsWithoutInterpretingCommands() {
        CurrentUser dad = member("dad@example.com", "dad");
        Agent agent = agents.findByCode("dad").orElseThrow();
        agent.assignFlow(ResearchAndBuildFlow.NAME);
        agents.save(agent);
        stub().willAnswer(command -> {
            if (command.input().contains("조사할 것과 만들 것을 나눈다")) {
                return answered("chief", "{\"research\":\"자료\",\"build\":\"구현\"}");
            }
            return answered("step", "단계 답");
        });

        ChatTurn turn = chat.send(dad, null, "/shopping 이번 주", "dad");

        assertThat(stub().received())
                .as("Hermes 에 보낸 것")
                .isNotEmpty()
                .allSatisfy(command -> assertThat(command.input()).doesNotContain("skill_view(name="));
        assertThat(stub().received())
                .anySatisfy(command -> assertThat(command.input()).contains("/shopping 이번 주"));
        assertThat(skillUses.count()).as("스킬 이력").isZero();
        assertThat(messages.findByConversationIdOrderByIdAsc(turn.conversationId()))
                .first()
                .satisfies(message -> assertThat(message.content()).isEqualTo("/shopping 이번 주"));
    }

    @Test
    @DisplayName("다시 생성도 바꾼 입력을 보내고 그 실행에 COMMAND 이력을 남긴다")
    void regenerationAlsoSendsChangedInputAndRecordsCommandHistory() {
        CurrentUser dad = member("dad@example.com", "dad");
        skillsOf("dad", true, "shopping");
        stub().willReturnInOrder(answered("first", "첫 답"), answered("second", "새 답"));
        ChatTurn first = chat.send(dad, null, "/shopping 이번 주", "dad");

        List<ChatEvent> relayed = new ArrayList<>();
        chat.regenerate(dad, first.conversationId(), relayed::add);

        String expected = artifactService.agentPreamble(
                        conversations.findById(first.conversationId()).orElseThrow())
                + new SkillCommand("shopping", "이번 주").hermesInput();
        assertThat(stub().received()).extracting(command -> command.input()).containsExactly(expected, expected);
        Long regenerated = relayed.getLast().executionId();
        assertThat(regenerated).isNotEqualTo(first.executionId());
        assertThat(skillUsesOf(regenerated)).singleElement().satisfies(use -> {
            assertThat(use.skillName()).isEqualTo("shopping");
            assertThat(use.source()).isEqualTo(SkillUseSource.COMMAND);
        });
    }

    @Test
    @DisplayName("켜진 스킬 목록은 에이전트마다 캐시하고 SkillsChanged 를 받으면 다시 읽는다")
    void cachesEnabledSkillListPerAgentAndRereadsOnSkillsChanged() {
        // 캐시가 읽은 시각부터 재도록 시계를 멈춘다. 흐르는 시계에서는 아래 29초가 보관 시간을 넘길 수 있다
        clock.set(Instant.now());
        CurrentUser dad = member("dad@example.com", "dad");
        Long agentId = agents.findByCode("dad").orElseThrow().id();
        skillsOf("dad", true, "shopping");
        stub().willReturn(answered("run-1", "네"));
        ChatTurn first = chat.send(dad, null, "/shopping 하나", "dad");
        chat.send(dad, first.conversationId(), "/shopping 둘", null);
        verify(skillService, times(1)).commandList(argThat(agent -> "dad".equals(agent.code())));

        // 목록이 바뀌어도 사건 전에는 들고 있던 목록으로 판별한다. 캐시 시간 안이다.
        skillsOf("dad", true, "shopping:off");
        clock.advance(Duration.ofSeconds(29));
        chat.send(dad, first.conversationId(), "/shopping 셋", null);

        applicationEvents.publishEvent(new SkillsChanged(agentId));

        assertCode(() -> chat.send(dad, first.conversationId(), "/shopping 넷", null), ErrorCode.SKILL_COMMAND_UNKNOWN);
        assertThat(stub().received()).as("Hermes 에 보낸 것").hasSize(3);
    }

    /** 이 검사가 쓰는 표를 비운다. 컨텍스트를 함께 쓰는 다음 검사에 행을 남기지 않게 검사 뒤에도 부른다. */
    private void deleteRows() {
        skillUses.deleteAll();
        contextSources.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        tierDefinitions.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        memoryRepository.deleteAll();
        users.deleteAll();
    }
}
