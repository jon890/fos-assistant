package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.chat.presentation.ChatEventStreams;
import com.bifos.assistant.chat.presentation.ConversationEventController;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
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
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 열린 대화 창이 자동 turn 의 사건을 받는 대화 단위 SSE 를 본다.
 *
 * <p>스트림은 끝나지 않으므로 끝까지 기다리지 않고, 사건을 낸 뒤 그때까지 쓰인 응답을 읽는다. 도구의 명령
 * 원문을 관리자에게만 싣는 판정은 ADR-038 이 근거다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ConversationEventControllerTest {

    private static final String COMMAND = "python3 run.py";

    @Autowired ChatService chat;
    @Autowired ConversationAccess access;
    @Autowired ConversationEventHub hub;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired AgentRepository agents;
    @Autowired MemoryRepository memories;
    @Autowired AppUserRepository users;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void 준비한다() {
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
        mvc = MockMvcBuilders
                .standaloneSetup(new ConversationEventController(currentUser, access, hub,
                        new ChatEventStreams(Duration.ofSeconds(20))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 주인이_구독하면_대화에_낸_system_사건을_받는다() throws Exception {
        CurrentUser dad = signedIn("events-dad", UserRole.ADMIN);
        Conversation conversation = conversationOf(dad);
        MvcResult subscribed = subscribe(conversation.publicId());

        hub.publish(conversation.id(), ChatEvent.system(conversation.publicId(), 7L, "위임 결과가 도착했습니다"));

        List<JsonNode> events = received(subscribed);
        assertThat(events).as("받은 사건: %s", events).hasSize(1);
        assertThat(events.getFirst().path("type").asString()).isEqualTo("system");
        assertThat(events.getFirst().path("text").asString()).isEqualTo("위임 결과가 도착했습니다");
        assertThat(events.getFirst().path("messageId").asLong()).isEqualTo(7L);
        assertThat(events.getFirst().path("conversationId").asString())
                .isEqualTo(conversation.publicId().toString());
    }

    @Test
    void 다른_대화에_낸_사건은_받지_않는다() throws Exception {
        CurrentUser dad = signedIn("events-other-dad", UserRole.ADMIN);
        Conversation watched = conversationOf(dad);
        Conversation other = conversationOf(dad);
        MvcResult subscribed = subscribe(watched.publicId());

        hub.publish(other.id(), ChatEvent.system(other.publicId(), 8L, "다른 대화의 알림"));

        assertThat(received(subscribed)).isEmpty();
    }

    @Test
    void 남의_대화는_도는_turn_조회와_같은_오류다() throws Exception {
        CurrentUser dad = signedIn("events-owner", UserRole.ADMIN);
        UUID dadsConversation = conversationOf(dad).publicId();
        signedIn("events-stranger", UserRole.MEMBER);

        MockHttpServletResponse events = mvc.perform(get("/api/v1/chat/conversations/{id}/events",
                dadsConversation)).andReturn().getResponse();
        MockHttpServletResponse running = MockMvcBuilders
                .standaloneSetup(new ChatController(chat, currentUser, users,
                        null, access, new ChatEventStreams(Duration.ofSeconds(20)), null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(get("/api/v1/chat/conversations/{id}/running", dadsConversation))
                .andReturn().getResponse();

        assertThat(events.getStatus()).isEqualTo(404).isEqualTo(running.getStatus());
        assertThat(events.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(running.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void MEMBER_역할에게는_tool_사건의_명령_원문을_싣지_않는다() throws Exception {
        CurrentUser kid = signedIn("events-kid", UserRole.MEMBER);
        Conversation conversation = conversationOf(kid);
        MvcResult subscribed = subscribe(conversation.publicId());

        hub.publish(conversation.id(), ChatEvent.tool("terminal", COMMAND, ChatEvent.STARTED, null, null));

        JsonNode tool = received(subscribed).getFirst();
        assertThat(tool.path("toolName").asString()).isEqualTo("terminal");
        assertThat(tool.hasNonNull("detail")).as("MEMBER 역할의 terminal 사건에 detail 이 실렸다: %s", tool).isFalse();
    }

    @Test
    void ADMIN_역할에게는_tool_사건의_명령_원문을_싣는다() throws Exception {
        CurrentUser dad = signedIn("events-admin", UserRole.ADMIN);
        Conversation conversation = conversationOf(dad);
        MvcResult subscribed = subscribe(conversation.publicId());

        hub.publish(conversation.id(), ChatEvent.tool("terminal", COMMAND, ChatEvent.STARTED, null, null));

        JsonNode tool = received(subscribed).getFirst();
        assertThat(tool.path("detail").asString()).isEqualTo(COMMAND);
    }

    /** 이 역할의 사용자와 그 사람의 에이전트를 만들고 로그인한 것으로 둔다. */
    private CurrentUser signedIn(String name, UserRole role) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, role));
        agents.save(Agent.of(name, name, name,
                "http://agent-runtime.test/p/" + name,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, user.id()));
        CurrentUser current = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), role);
        when(currentUser.require()).thenReturn(current);
        return current;
    }

    private Conversation conversationOf(CurrentUser user) {
        return chat.startEmpty(user, user.displayName());
    }

    private MvcResult subscribe(UUID conversationId) throws Exception {
        return mvc.perform(get("/api/v1/chat/conversations/{id}/events", conversationId))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    /** 지금까지 쓰인 응답에서 {@code data:} 줄마다 사건 하나로 읽는다. */
    private List<JsonNode> received(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                events.add(json.readTree(line.substring("data:".length())));
            }
        }
        return events;
    }
}
