package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
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
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * 대화 스트림의 {@code tool} 사건이 도구의 명령 원문을 관리자에게만 싣는지 본다.
 *
 * <p>판정은 컨트롤러가 서비스에 넘기는 사건 소비자에서 하므로 컨트롤러를 거쳐 SSE 응답을 끝까지 읽는다.
 * 근거는 ADR-038 에 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ToolDetailStreamTest.StubRuntime.class)
class ToolDetailStreamTest {

    private static final String COMMAND = "python3 run.py";
    private static final String QUERY = "제주 날씨";

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
                        null))
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
                .open(any(), any(), any(), any(), any());
    }

    /** 이 역할의 사용자와 그 사람의 에이전트를 만들고 로그인한 것으로 둔다. */
    private CurrentUser signedIn(String name, UserRole role) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, role));
        agents.save(Agent.of(
                name,
                name,
                name,
                "http://agent-runtime.test/p/" + name,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id()));
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
