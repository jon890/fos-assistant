package com.bifos.assistant.chat.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record SendMessageRequest(
            Long conversationId, @NotBlank @Size(max = 8000) String text, String agentCode) {
    }

    public record SendMessageResponse(Long conversationId, Long executionId, String assistantText) {
    }

    /**
     * @param hasChildren 이 답이 여러 실행으로 만들어졌다. 화면이 이때만 실행 나무로 가는 길을 보인다
     * @param switchedTo 앞 provider 가 막혀 넘어간 경우 그 답을 만든 provider 와 모델. 넘어가지
     *     않았으면 null
     */
    public record MessageView(
            Long id,
            String role,
            String content,
            String senderName,
            Long executionId,
            boolean hasChildren,
            String switchedTo,
            Instant createdAt) {
    }

    public record ConversationView(Long id, String title, String agentCode, String agentName,
            Instant updatedAt) {
    }
}
