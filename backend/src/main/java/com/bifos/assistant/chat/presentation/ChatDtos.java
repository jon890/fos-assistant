package com.bifos.assistant.chat.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record SendMessageRequest(
            Long conversationId, @NotBlank @Size(max = 8000) String text, String workspaceCode) {
    }

    public record SendMessageResponse(Long conversationId, Long executionId, String assistantText) {
    }

    public record MessageView(Long id, String role, String content, Long executionId, Instant createdAt) {
    }

    public record ConversationView(Long id, String title, String workspaceCode, Instant updatedAt) {
    }
}
