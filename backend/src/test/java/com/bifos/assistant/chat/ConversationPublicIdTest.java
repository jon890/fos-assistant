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

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.ProviderStateRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
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
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

    @Autowired ChatService chat;
    @Autowired ConversationAccess access;
    @Autowired AgentService agentService;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired AgentRepository agents;
    @Autowired AgentModelSelector modelSelector;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired ProviderStateRepository providerStates;
    @Autowired MemoryRepository memories;
    @Autowired AppUserRepository users;
    @Autowired HermesRunsClient hermes;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void 준비한다() {
        ((StubHermesRunsClient) hermes).reset();
        ((StubHermesRunsClient) hermes).willReturn(HermesRunResult.of("run-one", "session-one", "completed", "답",
                "example-model-large", "anthropic", TokenUsage.empty()));
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        providerStates.deleteAll();
        modelOptions.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
        mvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chat, currentUser, users, agentService, access,
                        new ChatEventStreams(Duration.ofSeconds(20))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CurrentUser member(String name) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, UserRole.MEMBER));
        Agent agent = agents.save(Agent.of(name, name, name,
                "http://agent-runtime.test/p/" + name, "anthropic", "example-model-large",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, user.id()));
        modelSelector.seedFirst(agent, new ModelOption("anthropic", "example-model-large"));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.familyId(), user.role());
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
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(body).path("conversationId").asString());
    }

    private Conversation stored(UUID publicId) {
        return conversations.findAll().stream()
                .filter(conversation -> publicId.equals(conversation.publicId()))
                .findFirst().orElseThrow();
    }

    @Test
    void 새_대화는_v7_공개_식별자를_받고_목록이_같은_식별자를_낸다() throws Exception {
        CurrentUser dad = member("public-dad");
        UUID id = started(dad);

        assertThat(id.version()).isEqualTo(7);
        mvc.perform(get("/api/v1/chat/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    @Test
    void 공개_식별자로_메시지를_읽고_이름을_바꾸고_지운다() throws Exception {
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
        mvc.perform(delete("/api/v1/chat/conversations/{id}", id))
                .andExpect(status().isNoContent());

        assertThat(stored(id).deletedAt()).isNotNull();
        mvc.perform(get("/api/v1/chat/conversations/{id}/messages", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()));
    }

    @Test
    void 남의_대화의_공개_식별자는_없는_대화와_같다() throws Exception {
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
    void 모양이_틀린_식별자는_입력_오류다() throws Exception {
        signedIn(member("public-dad"));

        mvc.perform(get("/api/v1/chat/conversations/abc/messages"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    void 공개_식별자로_도는_turn을_물으면_돌지_않는_대화는_running이_false이고_나머지는_null이다() throws Exception {
        UUID id = started(member("public-dad"));

        mvc.perform(get("/api/v1/chat/conversations/{id}/running", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.executionId").doesNotExist())
                .andExpect(jsonPath("$.startedAt").doesNotExist());
    }

    @Test
    void 도는_turn을_물을_때_모양이_틀린_식별자는_입력_오류다() throws Exception {
        signedIn(member("public-dad"));

        mvc.perform(get("/api/v1/chat/conversations/abc/running"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    void 옛_번호로는_주인에게만_공개_식별자를_알려_준다() throws Exception {
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
    void 스트리밍_보내기의_started_와_done_이_같은_공개_식별자를_싣는다() throws Exception {
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
    void 스트리밍_보내기에서_남의_대화는_error_사건이다() throws Exception {
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
        MvcResult result = mvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
        // 스트림은 가상 스레드에서 돈다. 끝날 때까지 기다린 뒤 응답을 읽는다.
        result.getAsyncResult(10_000);
        String body = mvc.perform(asyncDispatch(result)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                events.add(json.readTree(line.substring("data:".length())));
            }
        }
        return events;
    }
}
