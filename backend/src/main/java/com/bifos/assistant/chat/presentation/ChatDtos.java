package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ActivitySummary;
import com.bifos.assistant.chat.application.PendingQueue;
import com.bifos.assistant.chat.application.RunningTurn;
import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.ModelChoice;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChatDtos {

    private ChatDtos() {}

    /**
     * @param conversationId 이어 쓸 대화의 공개 식별자. 없으면 새 대화를 만든다
     * @param attachmentIds 이 메시지와 함께 보낼 첨부 번호들. 화면이 비워 보내든 빼고 보내든 같게 다루려고
     *     null 을 빈 목록으로 바꾼다
     */
    public record SendMessageRequest(
            UUID conversationId, @NotBlank @Size(max = 8000) String text, String agentCode, List<Long> attachmentIds) {

        public SendMessageRequest {
            attachmentIds = attachmentIds == null ? List.of() : attachmentIds;
        }
    }

    /** 사진을 먼저 올리거나 첫 메시지 전에 모델을 고르려고 메시지 없이 대화를 만든다. */
    public record StartConversationRequest(String agentCode) {}

    public record StartConversationResponse(UUID conversationId) {}

    public record SendMessageResponse(UUID conversationId, Long executionId, String assistantText) {}

    /**
     * @param hasChildren 이 답이 여러 실행으로 만들어졌다. 화면이 이때만 실행 나무로 가는 길을 보인다
     * @param switchedTo 앞 provider 가 막혀 넘어간 경우 그 답을 만든 provider 와 모델. 넘어가지
     *     않았으면 null
     * @param attachments 이 메시지에 붙은 첨부. 지워진 것도 자리를 남기려고 담는다. 없으면 빈 목록
     * @param artifacts 이 답의 turn 이 대화 폴더에 만든 HTML. 지워진 것도 담는다. 사용자 메시지와 결과물이 없는
     *     답은 빈 목록
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
            String status) {}

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

    /** 응답 중에 보내는 메시지다. 글만 받는다. */
    public record PendingMessageRequest(
            @NotBlank @Size(max = 8000) String text) {}

    public record PendingMessageView(Long id, String text, Instant createdAt) {
        static PendingMessageView from(ChatPendingMessage message) {
            return new PendingMessageView(message.id(), message.content(), message.createdAt());
        }
    }

    /** @param held 멈춰 두었다. 사용자가 「보내기」 를 누를 때까지 보내지 않는다 */
    public record PendingQueueView(boolean held, List<PendingMessageView> items) {
        static PendingQueueView from(PendingQueue queue) {
            return new PendingQueueView(
                    queue.held(),
                    queue.items().stream().map(PendingMessageView::from).toList());
        }
    }

    /**
     * 첨부 한 장이다. 본문과 주소를 담지 않는다. 화면이 대화의 공개 식별자와 첨부 번호로 본문 경로를 만든다.
     *
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
     *
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
     *
     * @param id 대화의 공개 식별자
     * @param agentCode 대화의 에이전트 코드. 에이전트 행이 없거나 대화에 에이전트가 없으면 null
     * @param agentName 대화의 에이전트 이름. 에이전트 행이 없거나 대화에 에이전트가 없으면 null
     * @param provider 이 대화에서 고른 provider. 고르지 않았으면 null
     * @param model 이 대화에서 고른 모델. 고르지 않았으면 null 이고 그 profile 의 기본 모델로 돈다
     * @param reasoningEffort 이 대화에서 고른 effort. 고르지 않았으면 null
     */
    public record ConversationView(
            UUID id,
            String title,
            String agentCode,
            String agentName,
            Instant updatedAt,
            String provider,
            String model,
            String reasoningEffort) {}

    /** 옛 대화 번호로 찾은 대화의 공개 식별자다. 옛 링크를 새 주소로 넘길 때만 쓴다. */
    public record ConversationRefView(UUID id) {}

    public record RenameConversationRequest(@NotNull String title) {}

    /** 대화에서 쓸 모델과 effort 다. 모두 비워 보내면 그 profile 의 기본값으로 돌아간다. */
    public record ChooseModelRequest(String provider, String model, String reasoningEffort) {

        /** 요청 값을 검증해 선택으로 바꾼다. 맞지 않으면 {@code VALIDATION_FAILED} 다. */
        public ModelChoice toChoice() {
            return ModelChoice.of(provider, model, reasoningEffort);
        }
    }

    /** 그 에이전트의 profile 로 고를 수 있는 모델이다. */
    public record ModelOptionsView(
            String defaultProvider, String defaultModel, List<ProviderView> providers, List<String> reasoningEfforts) {}

    /**
     * @param provider Hermes 가 부르는 provider 이름
     * @param name 화면에 보일 이름
     * @param models 그 provider 의 모델 이름
     * @param reasoningCapable 모델 이름을 열쇠로 한 reasoning 지원 여부. Hermes 가 밝히지 않은 모델은 참으로 본다
     */
    public record ProviderView(
            String provider, String name, List<String> models, Map<String, Boolean> reasoningCapable) {}
}
