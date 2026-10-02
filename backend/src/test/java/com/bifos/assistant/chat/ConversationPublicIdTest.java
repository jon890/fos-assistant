package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.chat.presentation.ChatEventStreams;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화를 공개 식별자로만 주고받는지 본다.
 *
 * <p>경로 변수의 형식 오류와 오류 응답의 상태 코드는 컨트롤러 밖에서 정해져 MockMvc 로 부른다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ConversationPublicIdTest.StubRuntime.class)
class ConversationPublicIdTest {

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
    }

    private CurrentUser member(String name) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, UserRole.MEMBER, Instant.now()));
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
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private void signedIn(CurrentUser user) {
        when(currentUser.require()).thenReturn(user);
    }

    /** 보내기로 새 대화를 만들고 응답의 공개 식별자를 돌려준다. */
    private UUID started(CurrentUser user) throws Exception {
        signedIn(user);
        String body = mvc.perform(post("/api/v1/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"첫 질문\",\"agentCode\":\"" + user.displayName() + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(body).path("conversationId").asString());
    }

    private Conversation stored(UUID publicId) {
        return conversations.findAll().stream()
                .filter(conversation -> publicId.equals(conversation.publicId()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("새 대화는 v7 공개 식별자를 받고 목록이 같은 식별자를 낸다")
    void newConversationGetsV7PublicIdAndListReturnsSameId() throws Exception {
        CurrentUser dad = member("public-dad");
        UUID id = started(dad);

        assertThat(id.version()).isEqualTo(7);
        mvc.perform(get("/api/v1/chat/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()));
    }

    @Test
    @DisplayName("대화 한 줄을 공개 식별자로 읽고 남의 대화는 없는 대화로 답한다")
    void readsOneConversationAndHidesOthers() throws Exception {
        CurrentUser dad = member("public-dad");
        CurrentUser kid = member("public-kid");
        UUID id = started(dad);

        signedIn(dad);
        mvc.perform(get("/api/v1/chat/conversations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        signedIn(kid);
        mvc.perform(get("/api/v1/chat/conversations/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("공개 식별자로 메시지를 읽고 이름을 바꾸고 지운다")
    void readsMessagesRenamesAndDeletesByPublicId() throws Exception {
        CurrentUser dad = member("public-dad");
        UUID id = started(dad);

        mvc.perform(get("/api/v1/chat/conversations/{id}/messages", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("첫 질문"));
        mvc.perform(patch("/api/v1/chat/conversations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"새 이름\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("새 이름"));
        mvc.perform(delete("/api/v1/chat/conversations/{id}", id)).andExpect(status().isNoContent());

        assertThat(stored(id).deletedAt()).isNotNull();
        mvc.perform(get("/api/v1/chat/conversations/{id}/messages", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("남의 대화의 공개 식별자는 없는 대화와 같다")
    void othersPublicIdIsSameAsMissingConversation() throws Exception {
        CurrentUser dad = member("public-dad");
        CurrentUser kid = member("public-kid");
        UUID id = started(dad);
        signedIn(kid);

        mvc.perform(get("/api/v1/chat/conversations/{id}/messages", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));
        mvc.perform(delete("/api/v1/chat/conversations/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));
        assertThat(stored(id).deletedAt()).isNull();
    }

    @Test
    @DisplayName("모양이 틀린 식별자는 입력 오류다")
    void malformedIdIsInputError() throws Exception {
        signedIn(member("public-dad"));

        mvc.perform(get("/api/v1/chat/conversations/abc/messages"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("공개 식별자로 도는 turn을 물으면 돌지 않는 대화는 running이 false이고 나머지는 null이다")
    void runningQueryByPublicIdGivesFalseForIdleAndNullForRest() throws Exception {
        UUID id = started(member("public-dad"));

        mvc.perform(get("/api/v1/chat/conversations/{id}/running", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.executionId").doesNotExist())
                .andExpect(jsonPath("$.startedAt").doesNotExist());
    }

    @Test
    @DisplayName("도는 turn을 물을 때 모양이 틀린 식별자는 입력 오류다")
    void runningQueryWithMalformedIdIsInputError() throws Exception {
        signedIn(member("public-dad"));

        mvc.perform(get("/api/v1/chat/conversations/abc/running"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("옛 번호로는 주인에게만 공개 식별자를 알려 준다")
    void oldNumberRevealsPublicIdOnlyToOwner() throws Exception {
        CurrentUser dad = member("public-dad");
        CurrentUser kid = member("public-kid");
        UUID id = started(dad);
        UUID gone = started(dad);
        Long number = stored(id).id();
        Long goneNumber = stored(gone).id();
        mvc.perform(delete("/api/v1/chat/conversations/{id}", gone)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/chat/conversations/by-number/{number}", number))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get("/api/v1/chat/conversations/by-number/{number}", goneNumber))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));
        signedIn(kid);
        mvc.perform(get("/api/v1/chat/conversations/by-number/{number}", number))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("스트리밍 보내기의 started 와 done 이 같은 공개 식별자를 싣는다")
    void streamStartedAndDoneCarrySamePublicId() throws Exception {
        CurrentUser dad = member("public-dad");
        UUID id = started(dad);

        List<JsonNode> events = streamed(post("/api/v1/chat/messages/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + id + "\",\"text\":\"이어 묻기\"}"));

        JsonNode first = events.getFirst();
        JsonNode last = events.getLast();
        assertThat(first.path("type").asString()).isEqualTo("started");
        assertThat(last.path("type").asString()).isEqualTo("done");
        assertThat(first.path("conversationId").asString()).isEqualTo(id.toString());
        assertThat(last.path("conversationId").asString()).isEqualTo(id.toString());
    }

    @Test
    @DisplayName("스트리밍 보내기에서 남의 대화는 error 사건이다")
    void streamSendToOthersConversationIsErrorEvent() throws Exception {
        UUID id = started(member("public-dad"));
        signedIn(member("public-kid"));

        List<JsonNode> events = streamed(post("/api/v1/chat/messages/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + id + "\",\"text\":\"끼어들기\"}"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.path("type").asString()).isEqualTo("error");
            assertThat(event.path("code").asString()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND.name());
        });
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
