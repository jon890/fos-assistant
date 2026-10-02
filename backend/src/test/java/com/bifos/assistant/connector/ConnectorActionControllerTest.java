package com.bifos.assistant.connector;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorGrantView;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.presentation.ConnectorActionController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConnectorActionControllerTest {
    private static final CurrentUser MEMBER = new CurrentUser(7L, "member@example.com", "사용자", 1L, UserRole.MEMBER);
    private static final UUID CONVERSATION = UUID.fromString("019a0000-0000-7000-8000-000000000001");
    private static final UUID ACTION = UUID.fromString("019a0000-0000-7000-8000-000000000002");
    private static final ConnectorActionView PENDING = new ConnectorActionView(
            ACTION,
            "demo-notes",
            "write_note",
            "메모 쓰기",
            ToolRisk.WRITE,
            ActionStatus.PENDING,
            "{\"text\":\"안녕\"}",
            null,
            null,
            Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-10-02T00:00:00Z"),
            true,
            true);

    private final ConnectorActionService service = mock(ConnectorActionService.class);
    private final ConversationAccess conversations = mock(ConversationAccess.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ConnectorActionController(service, conversations, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(MEMBER);
    }

    @Test
    @DisplayName("대화의 승인 줄 목록은 공개 식별자를 대화 번호로 바꿔 읽고 약속한 칸을 모두 낸다")
    void listResolvesConversationAndReturnsContractFields() throws Exception {
        when(conversations.requireOwnId(MEMBER, CONVERSATION)).thenReturn(42L);
        when(service.listForConversation(MEMBER, 42L)).thenReturn(List.of(PENDING));

        mvc.perform(get("/api/v1/chat/conversations/{id}/connector-actions", CONVERSATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].length()").value(13))
                .andExpect(jsonPath("$[0].actionId").value(ACTION.toString()))
                .andExpect(jsonPath("$[0].connectorId").value("demo-notes"))
                .andExpect(jsonPath("$[0].toolName").value("write_note"))
                .andExpect(jsonPath("$[0].title").value("메모 쓰기"))
                .andExpect(jsonPath("$[0].risk").value("WRITE"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].argsJson").value("{\"text\":\"안녕\"}"))
                .andExpect(jsonPath("$[0].resultText").isEmpty())
                .andExpect(jsonPath("$[0].errorCode").isEmpty())
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].expiresAt").exists())
                .andExpect(jsonPath("$[0].grantAllowed").value(true))
                .andExpect(jsonPath("$[0].hiddenArgs").value(true));
    }

    @Test
    @DisplayName("남의 대화의 승인 줄 목록은 다른 대화 경로와 같은 CONVERSATION_NOT_FOUND 이고 줄을 읽지 않는다")
    void listOfForeignConversationIsNotFound() throws Exception {
        when(conversations.requireOwnId(MEMBER, CONVERSATION))
                .thenThrow(new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist"));

        mvc.perform(get("/api/v1/chat/conversations/{id}/connector-actions", CONVERSATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));

        verify(service, never()).listForConversation(any(), any());
    }

    @Test
    @DisplayName("승인은 grant 를 기간으로 읽어 넘기고 승인한 줄을 돌려준다")
    void approvePassesGrantPeriod() throws Exception {
        when(service.approve(MEMBER, ACTION, GrantPeriod.TODAY)).thenReturn(PENDING);

        mvc.perform(post("/api/v1/connector-actions/{id}/approve", ACTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grant\":\"TODAY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionId").value(ACTION.toString()));
    }

    @Test
    @DisplayName("grant 가 null 이거나 본문이 없으면 허락 없는 승인이다")
    void approveWithoutGrantPassesNull() throws Exception {
        when(service.approve(MEMBER, ACTION, null)).thenReturn(PENDING);

        mvc.perform(post("/api/v1/connector-actions/{id}/approve", ACTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grant\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/v1/connector-actions/{id}/approve", ACTION)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("grant 가 모르는 글이면 400 VALIDATION_FAILED 이고 승인하지 않는다")
    void approveWithUnknownGrantIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/connector-actions/{id}/approve", ACTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grant\":\"FOREVER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(service, never()).approve(any(), any(), any());
    }

    @Test
    @DisplayName("PENDING 이 아닌 줄의 승인은 409 CONNECTOR_ACTION_NOT_PENDING 이다")
    void approveOfFinishedActionIsConflict() throws Exception {
        when(service.approve(MEMBER, ACTION, null))
                .thenThrow(new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "not pending"));

        mvc.perform(post("/api/v1/connector-actions/{id}/approve", ACTION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONNECTOR_ACTION_NOT_PENDING"));
    }

    @Test
    @DisplayName("거절은 거절한 줄을 돌려주고, 남의 줄이면 404 CONNECTOR_ACTION_NOT_FOUND 다")
    void rejectReturnsActionOrNotFound() throws Exception {
        UUID foreign = UUID.fromString("019a0000-0000-7000-8000-000000000003");
        when(service.reject(MEMBER, ACTION)).thenReturn(PENDING);
        when(service.reject(MEMBER, foreign))
                .thenThrow(new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_FOUND, "no such action"));

        mvc.perform(post("/api/v1/connector-actions/{id}/reject", ACTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionId").value(ACTION.toString()));
        mvc.perform(post("/api/v1/connector-actions/{id}/reject", foreign))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTOR_ACTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("허락 목록은 grantId, connectorId, toolName, title, expiresAt 을 낸다")
    void grantsReturnContractFields() throws Exception {
        when(service.grants(MEMBER))
                .thenReturn(List.of(new ConnectorGrantView(
                        5L, "demo-notes", "write_note", "메모 쓰기", Instant.parse("2026-10-02T00:00:00Z"))));

        mvc.perform(get("/api/v1/connector-grants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].length()").value(5))
                .andExpect(jsonPath("$[0].title").value("메모 쓰기"))
                .andExpect(jsonPath("$[0].grantId").value(5))
                .andExpect(jsonPath("$[0].connectorId").value("demo-notes"))
                .andExpect(jsonPath("$[0].toolName").value("write_note"))
                .andExpect(jsonPath("$[0].expiresAt").exists());
    }

    @Test
    @DisplayName("허락을 거두면 본문 없는 204 이고, 남의 허락이면 404 CONNECTOR_ACTION_NOT_FOUND 다")
    void revokeGrantReturnsNoContentOrNotFound() throws Exception {
        doThrow(new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_FOUND, "no such grant"))
                .when(service)
                .revokeGrant(MEMBER, 6L);

        mvc.perform(delete("/api/v1/connector-grants/{id}", 5L))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(delete("/api/v1/connector-grants/{id}", 6L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTOR_ACTION_NOT_FOUND"));

        verify(service).revokeGrant(MEMBER, 5L);
    }
}
