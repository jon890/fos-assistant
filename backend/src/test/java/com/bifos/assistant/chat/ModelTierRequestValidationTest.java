package com.bifos.assistant.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 잘못된 요청은 서비스에 닿기 전에 같은 4xx 응답으로 거절한다. */
class ModelTierRequestValidationTest {

    private final ChatService chat = mock(ChatService.class);
    private final ModelTierService tiers = mock(ModelTierService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ChatController(chat, null, null, null, null, null, null, tiers, List.of()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("그룹 단계 배열이 없거나 null이고 원소가 null인 요청은 400이다")
    void rejectsMissingNullOrNullEntryTierDefinitions() throws Exception {
        for (String body : new String[] {"{}", "{\"tiers\":null}", "{\"tiers\":[null]}"}) {
            mvc.perform(put("/api/v1/chat/model-tiers/group")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(chat, tiers);
    }

    @Test
    @DisplayName("대화 단계 선택 모드가 없으면 내부 상속값을 저장하지 않고 400이다")
    void rejectsMissingSelectionMode() throws Exception {
        mvc.perform(put("/api/v1/chat/conversations/{id}/model-tier", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"FAST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(chat, tiers);
    }
}
