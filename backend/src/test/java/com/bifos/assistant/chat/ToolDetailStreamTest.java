package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.chat.presentation.ChatEventStreams;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화 스트림의 {@code tool} 사건이 도구의 명령 원문을, {@code subagent} 사건이 모델과 토큰을 관리자에게만
 * 싣는지 본다.
 *
 * <p>판정은 컨트롤러가 서비스에 넘기는 사건 소비자에서 하므로 컨트롤러를 거쳐 SSE 응답을 끝까지 읽는다.
 * 근거는 ADR-038 과 ADR-063 에 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ToolDetailStreamTest.StubRuntime.class)
class ToolDetailStreamTest {

    private static final String COMMAND = "python3 run.py";
    private static final String QUERY = "제주 날씨";
    private static final String GOAL = "숙소를 찾는다";
    private static final String SUBAGENT_MODEL = "example-model-small";

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired
    ChatService chat;

    @Autowired
    ConversationAccess access;

    @Autowired
    AgentService agentService;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    AgentRepository agents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    AppUserRepository users;

    @Autowired
    HermesRunsClient hermes;

    /** 도구 사건을 흘리려면 Hermes 의 사건 스트림을 우리가 열어 주어야 한다. */
    @MockitoBean
    HermesRunEventStream eventStream;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ((StubHermesRunsClient) hermes).reset();
        ((StubHermesRunsClient) hermes)
                .willReturn(HermesRunResult.of(
                        "run-one",
                        "session-one",
                        "completed",
                        "답",
                        "example-model-large",
                        "anthropic",
                        TokenUsage.empty()));
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(
                        chat,
                        currentUser,
                        new UserDisplayNameService(users),
                        agentService,
                        access,
                        new ChatEventStreams(Duration.ofSeconds(20)),
                        null,
                        mock(ModelTierService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        hermesStreams(
                new RunEvent("tool.started", null, "terminal", COMMAND, null, null),
                new RunEvent("tool.started", null, "web_search", QUERY, null, null),
                new RunEvent("run.completed", null, null, null, null, null));
    }

    @Test
    @DisplayName("MEMBER 역할에게는 terminal 의 명령 원문을 싣지 않고 검색어는 싣는다")
    void memberGetsSearchQueryButNotTerminalCommandText() throws Exception {
        CurrentUser kid = signedIn("stream-kid", UserRole.MEMBER);

        List<JsonNode> events = sent(kid);

        assertThat(toolEvent(events, "terminal").hasNonNull("detail"))
                .as("MEMBER 역할의 terminal 사건에 detail 이 실렸다: %s", toolEvent(events, "terminal"))
                .isFalse();
        assertThat(toolEvent(events, "web_search").path("detail").asString()).isEqualTo(QUERY);
        assertThat(storedDetails(events)).containsExactly(COMMAND, QUERY);
    }

    @Test
    @DisplayName("ADMIN 역할에게는 terminal 의 명령 원문을 싣는다")
    void adminGetsTerminalCommandText() throws Exception {
        CurrentUser dad = signedIn("stream-dad", UserRole.ADMIN);

        List<JsonNode> events = sent(dad);

        assertThat(toolEvent(events, "terminal").path("detail").asString()).isEqualTo(COMMAND);
        assertThat(toolEvent(events, "web_search").path("detail").asString()).isEqualTo(QUERY);
        assertThat(storedDetails(events)).containsExactly(COMMAND, QUERY);
    }

    @Test
    @DisplayName("MEMBER 역할이 답을 다시 만들어도 terminal 의 명령 원문을 싣지 않고 검색어는 싣는다")
    void memberRegeneratingReplyStillGetsNoTerminalCommandText() throws Exception {
        CurrentUser kid = signedIn("regenerate-kid", UserRole.MEMBER);
        ((StubHermesRunsClient) hermes)
                .willReturnInOrder(
                        HermesRunResult.of(
                                "run-first",
                                "session-one",
                                "completed",
                                "첫 답",
                                "example-model-large",
                                "anthropic",
                                TokenUsage.empty()),
                        HermesRunResult.of(
                                "run-again",
                                "session-one",
                                "completed",
                                "새 답",
                                "example-model-large",
                                "anthropic",
                                TokenUsage.empty()));
        Long number = chat.send(kid, null, "도구를 써 줘", kid.displayName()).conversationId();
        UUID conversationId = conversations.findById(number).orElseThrow().publicId();

        List<JsonNode> events =
                streamed(post("/api/v1/chat/conversations/{conversationId}/regenerate/stream", conversationId));

        assertThat(toolEvent(events, "terminal").hasNonNull("detail"))
                .as("MEMBER 역할이 다시 만든 답의 terminal 사건에 detail 이 실렸다: %s", toolEvent(events, "terminal"))
                .isFalse();
        assertThat(toolEvent(events, "web_search").path("detail").asString()).isEqualTo(QUERY);
        assertThat(storedDetails(events)).containsExactly(COMMAND, QUERY);
    }

    @Test
    @DisplayName("MEMBER 역할의 subagent 사건에는 모델과 토큰이 비고 목표와 걸린 시간은 남는다")
    void memberSubagentEventHasNoModelOrTokensButKeepsGoalAndDuration() throws Exception {
        CurrentUser kid = signedIn("subagent-kid", UserRole.MEMBER);
        hermesStreamsSubagent();

        List<JsonNode> events = sent(kid);

        assertThat(subagentEvents(events)).hasSize(2).allSatisfy(event -> {
            assertThat(event.hasNonNull("model")).as("model 이 실렸다: %s", event).isFalse();
            assertThat(event.hasNonNull("inputTokens"))
                    .as("inputTokens 가 실렸다: %s", event)
                    .isFalse();
            assertThat(event.hasNonNull("outputTokens"))
                    .as("outputTokens 가 실렸다: %s", event)
                    .isFalse();
            assertThat(event.path("goal").asString()).isEqualTo(GOAL);
            assertThat(event.path("subagentId").asString()).isEqualTo("sub-1");
        });
        assertThat(subagentEvents(events).get(1).path("durationMs").asLong()).isEqualTo(900L);
        assertThat(events.getLast().path("type").asString()).isEqualTo("done");
    }

    @Test
    @DisplayName("ADMIN 역할의 subagent 사건에는 모델과 토큰이 그대로 실린다")
    void adminSubagentEventKeepsModelAndTokens() throws Exception {
        CurrentUser dad = signedIn("subagent-dad", UserRole.ADMIN);
        hermesStreamsSubagent();

        List<JsonNode> events = sent(dad);

        assertThat(subagentEvents(events))
                .hasSize(2)
                .allSatisfy(event -> assertThat(event.path("model").asString()).isEqualTo(SUBAGENT_MODEL));
        JsonNode completed = subagentEvents(events).get(1);
        assertThat(completed.path("inputTokens").asLong()).isEqualTo(70L);
        assertThat(completed.path("outputTokens").asLong()).isEqualTo(9L);
        assertThat(completed.path("durationMs").asLong()).isEqualTo(900L);
    }

    @Test
    @DisplayName("switched 사건은 MEMBER 역할에게 보내지 않고 ADMIN 역할에게는 그대로 보낸다")
    void switchedEventIsDroppedForMemberAndKeptForAdmin() {
        ChatEvent switched = ChatEvent.switched("example-provider/" + SUBAGENT_MODEL);

        assertThat(switched.forViewer(viewer(UserRole.MEMBER))).isEmpty();
        assertThat(switched.forViewer(viewer(UserRole.ADMIN))).contains(switched);
    }

    @Test
    @DisplayName("오류 코드와 답 조각은 MEMBER 역할에게도 그대로 보낸다")
    void errorCodeAndDeltaAreKeptForMember() {
        ChatEvent error = ChatEvent.error("PROVIDER_BLOCKED", "blocked");
        ChatEvent delta = ChatEvent.delta("조각");

        assertThat(error.forViewer(viewer(UserRole.MEMBER))).contains(error);
        assertThat(delta.forViewer(viewer(UserRole.MEMBER))).contains(delta);
    }

    private static CurrentUser viewer(UserRole role) {
        return new CurrentUser(1L, "viewer@example.com", "viewer", 1L, role);
    }

    /** Hermes 가 모델과 토큰을 실은 하위 에이전트의 시작과 완료를 보낸 것처럼 만든다. */
    private void hermesStreamsSubagent() {
        hermesStreams(
                new RunEvent(
                        "subagent.start",
                        null,
                        "researcher",
                        null,
                        null,
                        null,
                        "sub-1",
                        GOAL,
                        SUBAGENT_MODEL,
                        null,
                        null,
                        null,
                        null,
                        null),
                new RunEvent(
                        "subagent.complete",
                        null,
                        "researcher",
                        null,
                        900L,
                        false,
                        "sub-1",
                        GOAL,
                        SUBAGENT_MODEL,
                        "child-session",
                        70L,
                        9L,
                        "completed",
                        null),
                new RunEvent("run.completed", null, null, null, null, null));
    }

    private static List<JsonNode> subagentEvents(List<JsonNode> events) {
        return events.stream()
                .filter(event -> "subagent".equals(event.path("type").asString()))
                .toList();
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
                .open(any(), any(), any(), any(), any(), anyBoolean());
    }

    /** 이 역할의 사용자와 그 사람의 에이전트를 만들고 로그인한 것으로 둔다. */
    private CurrentUser signedIn(String name, UserRole role) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, role, Instant.now()));
        agents.save(Agent.of(
                name,
                name,
                name,
                "http://agent-runtime.test/p/" + name,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                Instant.now()));
        CurrentUser current = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), role);
        when(currentUser.require()).thenReturn(current);
        return current;
    }

    /** 새 대화로 turn 하나를 스트림으로 보낸다. */
    private List<JsonNode> sent(CurrentUser user) throws Exception {
        return streamed(post("/api/v1/chat/messages/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"도구를 써 줘\",\"agentCode\":\"" + user.displayName() + "\"}"));
    }

    private static JsonNode toolEvent(List<JsonNode> events, String toolName) {
        return events.stream()
                .filter(event -> "tool".equals(event.path("type").asString()))
                .filter(event -> toolName.equals(event.path("toolName").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(toolName + " 의 tool 사건이 없다: " + events));
    }

    /** 이 turn 의 실행에 저장된 도구 사건의 {@code detail} 을 차례로 읽는다. */
    private List<String> storedDetails(List<JsonNode> events) {
        JsonNode done = events.getLast();
        assertThat(done.path("type").asString()).as("마지막 사건: %s", done).isEqualTo("done");
        long executionId = done.path("executionId").asLong();
        return executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId)).stream()
                .filter(event -> event.eventType() == ExecutionEventType.TOOL_STARTED)
                .map(ExecutionEvent::detail)
                .toList();
    }

    /** SSE 응답을 끝까지 받아 {@code data:} 줄마다 사건 하나로 읽는다. */
    private List<JsonNode> streamed(RequestBuilder builder) throws Exception {
        MvcResult result =
                mvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
        // 스트림은 가상 스레드에서 돈다. 끝날 때까지 기다린 뒤 응답을 읽는다.
        result.getAsyncResult(10_000);
        String body =
                mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                events.add(json.readTree(line.substring("data:".length())));
            }
        }
        return events;
    }
}
