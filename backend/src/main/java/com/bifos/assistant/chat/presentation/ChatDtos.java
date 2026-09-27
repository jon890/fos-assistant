package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.application.ActivitySummary;
import com.bifos.assistant.chat.application.RunningTurn;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatDtos {

    private ChatDtos() {
    }

    /**
     * @param conversationId 이어 쓸 대화의 공개 식별자. 없으면 새 대화를 만든다
     * @param attachmentIds 이 메시지와 함께 보낼 첨부 번호들. 화면이 비워 보내든 빼고 보내든 같게 다루려고
     *     null 을 빈 목록으로 바꾼다
     */
    public record SendMessageRequest(
            UUID conversationId,
            @NotBlank @Size(max = 8000) String text,
            String agentCode,
            List<Long> attachmentIds) {

        public SendMessageRequest {
            attachmentIds = attachmentIds == null ? List.of() : attachmentIds;
        }
    }

    /** 사진을 먼저 올리려고 메시지 없이 대화를 만든다. */
    public record StartConversationRequest(String agentCode) {
    }

    public record StartConversationResponse(UUID conversationId) {
    }

    public record SendMessageResponse(UUID conversationId, Long executionId, String assistantText) {
    }

    /**
     * @param hasChildren 이 답이 여러 실행으로 만들어졌다. 화면이 이때만 실행 나무로 가는 길을 보인다
     * @param switchedTo 앞 provider 가 막혀 넘어간 경우 그 답을 만든 provider 와 모델. 넘어가지
     *     않았으면 null
     * @param attachments 이 메시지에 붙은 첨부. 지워진 것도 자리를 남기려고 담는다. 없으면 빈 목록
     */
    public record MessageView(
            Long id,
            String role,
            String content,
            String senderName,
            Long executionId,
            boolean hasChildren,
            String switchedTo,
            Long replacesMessageId,
            Instant createdAt,
            List<AttachmentView> attachments,
            ActivitySummary activity,
            String status) {
    }

    public record StopResponse(String status) {}

    /**
     * 대화에 지금 도는 turn 이다. 같은 대화를 연 다른 창이 이것으로 답이 오는 중인지 안다.
     *
     * @param executionId 그 turn 의 뿌리 실행 번호. 돌지 않거나 아직 번호가 붙기 전이면 null
     * @param startedAt 그 실행이 시작한 시각. 번호가 없거나 실행 줄을 찾지 못하면 null
     */
    public record RunningTurnView(boolean running, Long executionId, Instant startedAt) {
        static RunningTurnView from(RunningTurn turn) {
            return new RunningTurnView(turn.running(), turn.executionId(), turn.startedAt());
        }
    }

    /**
     * 첨부 한 장이다. 본문과 주소를 담지 않는다. 화면이 대화의 공개 식별자와 첨부 번호로 본문 경로를 만든다.
     *
     * @param visible 아직 볼 수 있다. 보관 기간이 지났거나 사용자가 지웠으면 false
     * @param expiresAt 파일을 지울 시각
     */
    public record AttachmentView(
            Long id, String originalName, long byteSize, boolean visible, Instant expiresAt) {
        static AttachmentView from(ChatAttachment attachment) {
            return new AttachmentView(
                    attachment.id(),
                    attachment.originalName(),
                    attachment.byteSize(),
                    attachment.isVisible(),
                    attachment.expiresAt());
        }
    }

    /** @param id 대화의 공개 식별자 */
    public record ConversationView(UUID id, String title, String agentCode, String agentName,
            Instant updatedAt) {
    }

    /** 옛 대화 번호로 찾은 대화의 공개 식별자다. 옛 링크를 새 주소로 넘길 때만 쓴다. */
    public record ConversationRefView(UUID id) {
    }

    public record RenameConversationRequest(@NotNull String title) {
    }
}
