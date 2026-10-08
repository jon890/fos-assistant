package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.MemoryUseService;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.MemoryUseController;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionContextSourceRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 참고한 기억 경로가 HTTP 경계에서 돌려주는 응답 모양과 남의 대화를 숨기는 방식을 본다. 무엇을 낼지는
 * {@code MemoryUseServiceTest} 가 본다.
 */
@BackendIntegrationTest
class MemoryUseControllerTest {
    private static final String SECRET_BODY = "아무에게도 보이지 않을 본문";

    @Autowired
    MemoryUseService uses;

    @Autowired
    ConversationAccess access;

    @Autowired
    MemoryService memoryService;

    @Autowired
    MemoryRepository memories;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    ExecutionContextSourceRepository contextSources;

    @Autowired
    JdbcTemplate jdbc;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private MockMvc mvc;
    private CurrentUser dad;
    private CurrentUser kid;
    private Conversation conversation;
    private Memory memory;
    private AgentExecution run;

    @BeforeEach
    void setUp() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000L) + 6_000_000L;
        dad = new CurrentUser(suffix, "dad-" + suffix + "@example.com", "아빠", 1L, UserRole.ADMIN);
        kid = new CurrentUser(suffix + 1, "kid-" + suffix + "@example.com", "아이", 1L, UserRole.MEMBER);
        mvc = MockMvcBuilders.standaloneSetup(new MemoryUseController(uses, access, currentUser))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        conversation = conversations.save(Conversation.startedBy(dad.id(), "참고한 기억 경로", null, Instant.now()));
        memory = memoryService.create(
                dad, MemoryScope.USER, "딸 이름", SECRET_BODY, "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        run = McpCallSigner.running(
                executions,
                agents,
                dad.id(),
                conversation.id(),
                "chat-memory-use-controller",
                "fos-" + UUID.randomUUID());
        messages.save(ChatMessage.fromAssistant(conversation.id(), "답", run.id(), Instant.now()));
        contextSources.save(ExecutionContextSource.of(
                run.id(), 0, "MEMORY_FACTS", "memory:" + memory.id(), "INLINE", "FRESH", Instant.now()));
        when(currentUser.require()).thenReturn(dad);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM execution_context_source WHERE execution_id = ?", run.id());
        jdbc.update("DELETE FROM chat_message WHERE conversation_id = ?", conversation.id());
        jdbc.update("DELETE FROM agent_execution WHERE id = ?", run.id());
        jdbc.update("DELETE FROM conversation WHERE id = ?", conversation.id());
        memories.deleteById(memory.id());
    }

    @Test
    @DisplayName("대화의 주인은 200 과 항목 칸을 가진 JSON 배열을 받고 본문은 받지 않는다")
    void ownerGetsArrayWithoutBody() throws Exception {
        mvc.perform(get("/api/v1/chat/conversations/{id}/memory-uses", conversation.publicId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].executionId").value(run.id()))
                .andExpect(jsonPath("$[0].memoryId").value(memory.id()))
                .andExpect(jsonPath("$[0].title").value("딸 이름"))
                .andExpect(jsonPath("$[0].scope").value("USER"))
                .andExpect(jsonPath("$[0].via").value("FACTS"))
                .andExpect(jsonPath("$[0].content").doesNotExist())
                .andExpect(content().string(not(containsString(SECRET_BODY))));
    }

    @Test
    @DisplayName("다른 사용자는 없는 대화와 같은 404 를 받고 항목은 새지 않는다")
    void otherUserGetsSameNotFoundAsMissingConversation() throws Exception {
        when(currentUser.require()).thenReturn(kid);

        String foreign = mvc.perform(get("/api/v1/chat/conversations/{id}/memory-uses", conversation.publicId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_NOT_FOUND.name()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missing = mvc.perform(get("/api/v1/chat/conversations/{id}/memory-uses", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreign).as("남의 대화 응답과 없는 대화 응답").isEqualTo(missing);
    }
}
