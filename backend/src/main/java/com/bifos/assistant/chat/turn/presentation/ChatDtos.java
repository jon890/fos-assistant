package com.bifos.assistant.chat.turn.presentation;

import com.bifos.assistant.chat.turn.application.dto.ActivitySummary;
import com.bifos.assistant.chat.conversation.application.dto.MemoryUse;
import com.bifos.assistant.chat.conversation.application.dto.MemoryUseReaction;
import com.bifos.assistant.chat.turn.application.dto.PendingQueue;
import com.bifos.assistant.chat.turn.application.dto.RunningTurn;
import com.bifos.assistant.chat.conversation.application.dto.SourceReadSummary;
import com.bifos.assistant.chat.starter.application.dto.StarterSuggestions;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.conversation.domain.ChatPendingMessage;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.domain.type.ModelSelectionMode;
import com.bifos.assistant.memory.application.dto.CapturedMemory;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.model.domain.type.ModelTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChatDtos {
    /**
     * @param conversationId 이어 쓸 대화의 공개 식별자. 없으면 새 대화를 만든다
     * @param attachmentIds 이 메시지와 함께 보낼 첨부 번호들. 화면이 비워 보내든 빼고 보내든 같게 다루려고
     * null 을 빈 목록으로 바꾼다
     */
    public record SendMessageRequest(
        UUID conversationId, @NotBlank @Size(max = 8000) String text, String agentCode, List<Long> attachmentIds) {

        public SendMessageRequest {
            attachmentIds = attachmentIds == null ? List.of() : attachmentIds;
        }
    }

    /** 사진을 먼저 올리거나 첫 메시지 전에 모델을 고르려고 메시지 없이 대화를 만든다. */
    public record StartConversationRequest(String agentCode) {
    }

    public record StartConversationResponse(UUID conversationId) {
    }

    public record SendMessageResponse(UUID conversationId, Long executionId, String assistantText) {
    }

    /**
     * @param hasChildren 이 답이 여러 실행으로 만들어졌다. 화면이 이때만 실행 트리로 가는 길을 보인다
     * @param switchedTo 앞 provider 가 막혀 넘어간 경우 그 답을 만든 provider 와 모델. 넘어가지
     * 않았으면 null
     * @param attachments 이 메시지에 붙은 첨부. 지워진 것도 자리를 남기려고 담는다. 없으면 빈 목록
     * @param artifacts 이 답의 turn 이 대화 폴더에 만든 HTML. 지워진 것도 담는다. 사용자 메시지와 결과물이 없는
     * 답은 빈 목록
     * @param delivery 이 알림 줄이 묶음의 마지막 시도의 마지막 알림 줄이면 그 묶음의 번호와 상태. 아니면 null
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
        List<ArtifactView> artifacts,
        ActivitySummary activity,
        String status,
        DeliveryView delivery,
        SourceReadSummary sourceReads) {
    }

    /**
     * 알림 줄 아래에 그리는 전달 묶음이다(ADR-075). 오류 코드는 싣지 않는다. 원인은 관리자 영역의 실행 상세가 보인다.
     * @param id 전달 묶음 번호. 다시 전달할 때 이 번호로 부른다
     * @param status {@code DELIVERING}, {@code DELIVERED}, {@code FAILED}, {@code STOPPED} 가운데 하나
     */
    public record DeliveryView(Long id, String status) {
    }

    public record StopResponse(String status) {
    }

    /**
     * 대화에 지금 도는 turn 이다. 같은 대화를 연 다른 창이 이것으로 답이 오는 중인지 안다.
     * @param executionId 그 turn 의 루트 실행 번호. 돌지 않거나 아직 번호가 붙기 전이면 null
     * @param startedAt 그 실행이 시작한 시각. 번호가 없거나 실행 줄을 찾지 못하면 null
     */
    public record RunningTurnView(boolean running, Long executionId, Instant startedAt) {
        static RunningTurnView from(RunningTurn turn) {
            return new RunningTurnView(turn.running(), turn.executionId(), turn.startedAt());
        }
    }

    /** 응답 중에 보내는 메시지다. 글만 받는다. */
    public record PendingMessageRequest(
        @NotBlank @Size(max = 8000) String text) {
    }

    public record PendingMessageView(Long id, String text, Instant createdAt) {
        static PendingMessageView from(ChatPendingMessage message) {
            return new PendingMessageView(message.id(), message.content(), message.createdAt());
        }
    }

    /** @param held 멈춰 두었다. 사용자가 「보내기」 를 누를 때까지 보내지 않는다 */
    public record PendingQueueView(boolean held, List<PendingMessageView> items) {
        public static PendingQueueView from(PendingQueue queue) {
            return new PendingQueueView(
                queue.held(),
                queue.items().stream().map(PendingMessageView::from).toList());
        }
    }

    /**
     * 첨부 한 장이다. 본문과 주소를 담지 않는다. 화면이 대화의 공개 식별자와 첨부 번호로 본문 경로를 만든다.
     * @param visible 아직 볼 수 있다. 보관 기간이 지났거나 사용자가 지웠으면 false
     * @param expiresAt 파일을 지울 시각
     */
    public record AttachmentView(Long id, String originalName, long byteSize, boolean visible, Instant expiresAt) {
        static AttachmentView from(ChatAttachment attachment) {
            return new AttachmentView(
                attachment.id(),
                attachment.originalName(),
                attachment.byteSize(),
                attachment.isVisible(),
                attachment.expiresAt());
        }
    }

    /**
     * 답에 묶인 결과물 파일 하나다. 화면이 대화의 공개 식별자와 이 경로로 본문 주소를 만든다.
     * @param path 대화 폴더 안의 상대 경로. {@code /} 로 나눈다
     * @param byteSize 답에 묶을 때의 크기
     * @param deleted 보관 기간이 지나 파일을 지웠다
     */
    public record ArtifactView(String path, long byteSize, boolean deleted) {
        static ArtifactView from(ChatArtifact artifact) {
            return new ArtifactView(artifact.path(), artifact.byteSize(), artifact.isDeleted());
        }
    }

    /**
     * 대화 한 줄이다. 목록, 이름 바꾸기, 모델 선택이 같은 모양으로 돌려준다.
     * @param id 대화의 공개 식별자
     * @param agentCode 대화의 에이전트 코드. 에이전트 행이 없거나 대화에 에이전트가 없으면 null
     * @param agentName 대화의 에이전트 이름. 에이전트 행이 없거나 대화에 에이전트가 없으면 null
     * @param purpose 보통 대화인지 먼저 살펴보기의 점검 대화인지. 화면이 점검 대화를 알아보는 데 쓴다
     * @param taskId 이 대화를 만든 예약 작업의 공개 식별자. 작업이 만든 대화가 아니면 null
     * @param taskTitle 그 작업의 이름. 지운 작업도 이름을 싣는다. 작업이 만든 대화가 아니면 null
     */
    public record ConversationView(
        UUID id,
        String title,
        String agentCode,
        String agentName,
        Instant updatedAt,
        ModelSelectionMode modelSelectionMode,
        ModelTier modelTier,
        ConversationPurpose purpose,
        UUID taskId,
        String taskTitle) {
    }

    /**
     * 대화 목록의 한 쪽이다.
     * @param items 최근에 바뀐 것부터 담은 대화
     * @param nextCursor 다음 쪽을 읽을 때 {@code cursor} 로 넘기는 값. 마지막 쪽이면 null
     */
    public record ConversationPageView(List<ConversationView> items, String nextCursor) {
    }

    /** 옛 대화 번호로 찾은 대화의 공개 식별자다. 옛 링크를 새 주소로 넘길 때만 쓴다. */
    public record ConversationRefView(UUID id) {
    }

    public record RenameConversationRequest(@NotNull String title) {
    }

    public record ChooseModelTierRequest(@NotNull ModelSelectionMode mode, ModelTier tier) {
    }

    public record ModelTierDefinitionRequest(ModelTier tier, String provider, String model, String reasoningEffort) {
    }

    public record UpdateGroupModelTiersRequest(
        @NotNull List<@NotNull ModelTierDefinitionRequest> tiers, ModelTier defaultTier) {
    }

    /**
     * 새 대화 화면이 받는 추천 질문이다.
     * @param prompts 추천 질문. 보이는 차례대로다. {@code READY} 가 아니면 빈 목록
     * @param status {@code READY}, {@code GENERATING}, {@code NONE} 가운데 하나. {@code GENERATING} 이면
     * 화면이 잠시 뒤 다시 읽는다
     */
    public record StartersView(List<String> prompts, String status) {
        static StartersView from(StarterSuggestions suggestions) {
            return new StartersView(suggestions.prompts(), suggestions.status().name());
        }
    }

    /**
     * 대화의 답 아래에 그릴 기억 기록 하나다(ADR-20261007 / memory-remember).
     * <p>{@code kind} 는 {@code CREATED}, {@code UPDATED}, {@code PROPOSED} 이고 {@code status} 는 항목의 지금 승인 상태다. 민감
     * 항목은 본문을 싣지 않는다.
     */
    public record MemoryCaptureView(
        Long id,
        Long memoryId,
        Long executionId,
        String kind,
        String status,
        String title,
        String content,
        boolean sensitive,
        boolean alwaysInject,
        boolean externalRead,
        Instant createdAt) {
        static MemoryCaptureView from(CapturedMemory captured) {
            var capture = captured.capture();
            var memory = captured.memory();
            boolean sensitive = memory.sensitivity() == MemorySensitivity.SENSITIVE;
            return new MemoryCaptureView(
                capture.id(),
                memory.id(),
                capture.executionId(),
                capture.kind().name(),
                memory.status().name(),
                memory.title(),
                sensitive || memory.sealed() ? "" : memory.content(),
                sensitive,
                memory.alwaysInject(),
                capture.externalRead(),
                capture.createdAt());
        }
    }

    /**
     * 대화의 답 아래에 접어 보일 참고한 기억 하나다(ADR-20261008 / memory-facts).
     * <p>{@code via} 는 {@code ALWAYS}, {@code FACTS}, {@code SEARCH}, {@code READ} 이고 본문은 싣지 않는다.
     */
    /** 「참고한 기억」 항목 단추의 요청이다. 어느 답의 어느 기억인지와 누른 단추만 받는다. */
    public record MemoryUseReactionRequest(
        @NotNull Long executionId, @NotNull Long memoryId, @NotNull MemoryUseReaction reaction) {
    }

    public record MemoryUseView(Long executionId, Long memoryId, String title, String scope, String via) {
        static MemoryUseView from(MemoryUse use) {
            return new MemoryUseView(
                use.executionId(),
                use.memoryId(),
                use.title(),
                use.scope().name(),
                use.via().name());
        }
    }
}
