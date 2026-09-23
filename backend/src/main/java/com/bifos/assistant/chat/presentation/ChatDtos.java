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

    /** 사진을 먼저 올리려고 메시지 없이 대화를 만든다. */
    public record StartConversationRequest(String agentCode) {
    }

    public record StartConversationResponse(Long conversationId) {
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

    /**
     * 첨부 한 장이다. 본문과 주소를 담지 않는다. 화면이 대화 번호와 첨부 번호로 본문 경로를 만든다.
     *
     * @param visible 아직 볼 수 있다. 보관 기간이 지났거나 사용자가 지웠으면 false
     * @param expiresAt 파일을 지울 시각
     */
    public record AttachmentView(
            Long id, String originalName, long byteSize, boolean visible, Instant expiresAt) {
    }

    public record ConversationView(Long id, String title, String agentCode, String agentName,
            Instant updatedAt) {
    }
}
