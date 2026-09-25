package com.bifos.assistant.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerTest {

    private final ChatService chat = mock(ChatService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ChatController(
                    chat, currentUser, mock(AppUserRepository.class), mock(AgentService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 비스트리밍_메시지_경로는_질문_수정_번호를_거절한다() throws Exception {
        when(currentUser.require()).thenReturn(
                new CurrentUser(1L, "dad@example.com", "dad", 1L, UserRole.MEMBER));

        mvc.perform(post("/api/v1/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":3,\"text\":\"고친 질문\",\"editOfMessageId\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(chat);
    }
}
