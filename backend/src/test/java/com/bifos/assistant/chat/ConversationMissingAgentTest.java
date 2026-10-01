package com.bifos.assistant.chat;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.chat.presentation.ChatEventStreams;
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
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 대화가 가리키는 에이전트 행이 없을 때 목록과 한 줄 응답이 실패하지 않는지 본다.
 *
 * <p>목록은 그 줄의 에이전트 칸만 비우고 나머지를 돌려준다. 그 대화에 보내는 것은 없는 에이전트로 거절한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationMissingAgentTest {

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

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private MockMvc mvc;
    private CurrentUser dad;

    @BeforeEach
    void setUp() {
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
                        users,
                        agentService,
                        access,
                        new ChatEventStreams(Duration.ofSeconds(20)),
                        null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        AppUser user = users.save(AppUser.of("dad@example.com", "dad", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        when(currentUser.require()).thenReturn(dad);
    }

    private Agent agent(String code) {
        return agents.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                dad.id()));
    }

    private UUID conversationOf(Long agentId, String title) {
        return conversations
                .save(Conversation.startedBy(dad.id(), title, agentId))
                .publicId();
    }

    private String sendBody(UUID conversationId) {
        return "{\"conversationId\":\"" + conversationId + "\",\"text\":\"이어 묻기\"}";
    }

    @Test
    @DisplayName("에이전트 행이 없는 대화도 목록에 오고 그 줄의 에이전트 칸만 비어 있다")
    void conversationWithoutAgentRowStaysInListWithOnlyAgentColumnEmpty() throws Exception {
        Agent kept = agent("kept");
        Agent gone = agent("gone");
        UUID keptId = conversationOf(kept.id(), "남은 대화");
        UUID orphanId = conversationOf(gone.id(), "행이 없는 대화");
        agents.deleteById(gone.id());

        mvc.perform(get("/api/v1/chat/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == '" + keptId + "')].agentCode").value("kept"))
                .andExpect(jsonPath("$[?(@.id == '" + keptId + "')].agentName").value("kept"))
                .andExpect(jsonPath("$[?(@.id == '" + orphanId + "')].title").value("행이 없는 대화"))
                .andExpect(jsonPath("$[?(@.id == '" + orphanId + "')].agentCode")
                        .value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$[?(@.id == '" + orphanId + "')].agentName")
                        .value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    @DisplayName("에이전트 행이 없는 대화의 이름을 바꾸면 에이전트 칸이 빈 줄을 돌려준다")
    void renamingConversationWithoutAgentRowReturnsRowWithEmptyAgentColumn() throws Exception {
        Agent gone = agent("gone");
        UUID id = conversationOf(gone.id(), "옛 이름");
        agents.deleteById(gone.id());

        mvc.perform(patch("/api/v1/chat/conversations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"새 이름\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("새 이름"))
                .andExpect(jsonPath("$.agentCode").value(nullValue()));
    }

    @Test
    @DisplayName("에이전트 행이 없는 대화에 보내면 없는 에이전트로 거절한다")
    void sendingToConversationWithoutAgentRowIsRejectedAsMissingAgent() throws Exception {
        Agent gone = agent("gone");
        UUID id = conversationOf(gone.id(), "행이 없는 대화");
        agents.deleteById(gone.id());

        mvc.perform(post("/api/v1/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("에이전트 행이 없는 대화의 메시지 목록은 읽힌다")
    void messageListOfConversationWithoutAgentRowIsReadable() throws Exception {
        Agent gone = agent("gone");
        UUID id = conversationOf(gone.id(), "행이 없는 대화");
        agents.deleteById(gone.id());

        mvc.perform(get("/api/v1/chat/conversations/{id}/messages", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("에이전트 번호가 없는 대화도 목록에 오고 보내면 없는 에이전트로 거절한다")
    void conversationWithoutAgentIdStaysInListAndSendIsMissingAgent() throws Exception {
        UUID id = conversationOf(null, "에이전트 없는 대화");

        mvc.perform(get("/api/v1/chat/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].agentCode").value(nullValue()))
                .andExpect(jsonPath("$[0].agentName").value(nullValue()));
        mvc.perform(post("/api/v1/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_NOT_FOUND.name()));
    }
}
