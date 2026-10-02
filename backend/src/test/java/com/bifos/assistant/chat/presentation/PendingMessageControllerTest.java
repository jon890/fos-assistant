package com.bifos.assistant.chat.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.PendingMessageService;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.TurnHandle;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대기 줄 경로 넷의 응답 상태와 모양을 본다.
 *
 * <p>검사마다 그 대화의 turn 잠금을 잡아 둔다. 잡지 않으면 더한 글이 곧바로 turn 으로 나가 Hermes 를 부른다.
 * 잠금을 풀기 전에 대기 행을 비워 풀 때도 turn 이 열리지 않게 한다. 남은 행은 다른 검사 문맥의 기동 확인이 보낸다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PendingMessageControllerTest {

    @Autowired
    PendingMessageService pending;

    @Autowired
    ChatService chat;

    @Autowired
    ConversationAccess access;

    @Autowired
    TurnCancellation turns;

    @Autowired
    ChatPendingMessageRepository pendingRows;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private CurrentUser dad;
    private Conversation conversation;
    private TurnHandle running;

    @BeforeEach
    void setUp() {
        pendingRows.deleteAll();
        mvc = MockMvcBuilders.standaloneSetup(new PendingMessageController(pending, currentUser, access))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        dad = signedIn("pending-dad-" + UUID.randomUUID());
        conversation = chat.startEmpty(dad, dad.displayName());
        running = turns.open(dad.id(), conversation.id());
    }

    @AfterEach
    void tearDown() {
        pendingRows.deleteAll();
        turns.close(running);
    }

    @Test
    @DisplayName("더하면 201 과 대기 줄을, 읽으면 200 을, 취소하면 204 를, 풀면 202 를 돌려준다")
    void returnsStatusAndQueueForEachPath() throws Exception {
        MockHttpServletResponse added = mvc.perform(post(path(), conversation.publicId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"기다릴 글\"}"))
                .andReturn()
                .getResponse();

        assertThat(added.getStatus()).isEqualTo(201);
        JsonNode queue = bodyOf(added);
        assertThat(queue.path("held").asBoolean()).isFalse();
        assertThat(queue.path("items")).hasSize(1);
        JsonNode item = queue.path("items").get(0);
        assertThat(item.path("text").asString()).isEqualTo("기다릴 글");
        assertThat(item.hasNonNull("createdAt")).as("받은 줄: %s", item).isTrue();
        long pendingId = item.path("id").asLong();

        MockHttpServletResponse read =
                mvc.perform(get(path(), conversation.publicId())).andReturn().getResponse();
        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(bodyOf(read).path("items").get(0).path("id").asLong()).isEqualTo(pendingId);

        MockHttpServletResponse sent = mvc.perform(post(path() + "/send", conversation.publicId()))
                .andReturn()
                .getResponse();
        assertThat(sent.getStatus()).isEqualTo(202);
        assertThat(bodyOf(sent).path("items")).as("도는 turn 이 있어 아직 줄에 있다").hasSize(1);

        MockHttpServletResponse cancelled = mvc.perform(
                        delete(path() + "/{pendingId}", conversation.publicId(), pendingId))
                .andReturn()
                .getResponse();
        assertThat(cancelled.getStatus()).isEqualTo(204);
        assertThat(pendingRows.findByConversationIdOrderByIdAsc(conversation.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("대기 줄이 비어 있으면 held 가 거짓이고 items 가 빈 목록이다")
    void returnsEmptyQueueWhenNothingQueued() throws Exception {
        MockHttpServletResponse read =
                mvc.perform(get(path(), conversation.publicId())).andReturn().getResponse();

        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(bodyOf(read).path("held").asBoolean()).isFalse();
        assertThat(bodyOf(read).path("items").isArray()).isTrue();
        assertThat(bodyOf(read).path("items")).isEmpty();
    }

    @Test
    @DisplayName("빈 글은 400 VALIDATION_FAILED 다")
    void rejectsBlankText() throws Exception {
        MockHttpServletResponse response = add(conversation.publicId(), " ");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(bodyOf(response).path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(pendingRows.findByConversationIdOrderByIdAsc(conversation.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("8000자는 받고 8001자는 400 이다")
    void acceptsTextAtLimitAndRejectsOverLimit() throws Exception {
        MockHttpServletResponse over = add(conversation.publicId(), "가".repeat(8001));
        assertThat(over.getStatus()).isEqualTo(400);
        assertThat(bodyOf(over).path("code").asString()).isEqualTo("VALIDATION_FAILED");

        MockHttpServletResponse atLimit = add(conversation.publicId(), "가".repeat(8000));
        assertThat(atLimit.getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("남의 대화는 404 CONVERSATION_NOT_FOUND 다")
    void rejectsOthersConversation() throws Exception {
        UUID dadsConversation = conversation.publicId();
        signedIn("pending-stranger-" + UUID.randomUUID());

        MockHttpServletResponse added = add(dadsConversation, "글");
        MockHttpServletResponse read =
                mvc.perform(get(path(), dadsConversation)).andReturn().getResponse();

        assertThat(added.getStatus()).isEqualTo(404);
        assertThat(bodyOf(added).path("code").asString()).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(read.getStatus()).isEqualTo(404);
        assertThat(pendingRows.findByConversationIdOrderByIdAsc(conversation.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("없는 대기 메시지를 취소하면 404 PENDING_MESSAGE_NOT_FOUND 다")
    void rejectsCancellingMissingPendingMessage() throws Exception {
        MockHttpServletResponse response = mvc.perform(
                        delete(path() + "/{pendingId}", conversation.publicId(), Long.MAX_VALUE))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(bodyOf(response).path("code").asString()).isEqualTo("PENDING_MESSAGE_NOT_FOUND");
    }

    private static String path() {
        return "/api/v1/chat/conversations/{conversationId}/pending";
    }

    private MockHttpServletResponse add(UUID conversationId, String text) throws Exception {
        return mvc.perform(post(path(), conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ChatDtos.PendingMessageRequest(text))))
                .andReturn()
                .getResponse();
    }

    private JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 사용자와 그 사람의 에이전트를 만들고 로그인한 것으로 둔다. */
    private CurrentUser signedIn(String name) {
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
        CurrentUser current = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        when(currentUser.require()).thenReturn(current);
        return current;
    }
}
