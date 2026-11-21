package com.bifos.assistant.chat.reply.application;

import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.ReplyRef;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.reply.application.dto.ReplyPick;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 답장으로 집은 것이 이 대화의 것이고 쓸 수 있는지 본다(스펙 chat-reference REQ9, REQ14, 설계 「인터페이스와 데이터」).
 *
 * <p>대화를 잠그고 질문을 저장하기 전에 부른다. 거절하면 아무것도 저장하지 않는다. 없는 번호, 다른 대화의 번호, 남의 번호를 같은
 * {@code MESSAGE_NOT_FOUND} 로 답해 번호를 훑어 남의 것을 알아낼 수 없게 한다.
 */
@Component
@RequiredArgsConstructor
public class ReplyTargets {

    private final ChatMessageRepository messages;
    private final AttachmentService attachments;
    private final GeneratedImageService generatedImages;


    /**
     * @param conversation 요청자 것임을 이미 확인한 대화
     * @throws ApiException {@code MESSAGE_NOT_FOUND}, {@code REPLY_SOURCE_UNAVAILABLE}, {@code VALIDATION_FAILED}
     */
    public ReplyRef resolve(Conversation conversation, ReplyPick pick) {
        if (pick.attachmentId() != null && pick.imagePath() != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a reply picks one image at most");
        }
        ChatMessage message = messages.findById(pick.messageId())
            .filter(it -> conversation.id().equals(it.conversationId()))
            .filter(it -> it.role() == MessageRole.USER || it.role() == MessageRole.ASSISTANT)
            .filter(it -> it.steerState() == null)
            .orElseThrow(ReplyTargets::notFound);
        if (pick.attachmentId() != null) {
            ChatAttachment photo = attachments.allOf(conversation.id()).stream()
                .filter(it -> it.id().equals(pick.attachmentId()) && message.id().equals(it.messageId()))
                .findFirst()
                .orElseThrow(ReplyTargets::notFound);
            if (!attachments.inspectable(photo)) {
                throw unavailable();
            }
            return new ReplyRef(message.id(), photo.id(), null);
        }
        if (pick.imagePath() != null) {
            ChatArtifact image = generatedImages.imagesOfMessage(message.id()).stream()
                .filter(it -> Objects.equals(it.path(), pick.imagePath()))
                .findFirst()
                .orElseThrow(ReplyTargets::notFound);
            if (!generatedImages.isLive(image)) {
                throw unavailable();
            }
            return new ReplyRef(message.id(), null, image.id());
        }
        return new ReplyRef(message.id(), null, null);
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.MESSAGE_NOT_FOUND, "the picked message is not in this conversation");
    }


    private static ApiException unavailable() {
        return new ApiException(ErrorCode.REPLY_SOURCE_UNAVAILABLE, "the picked image is no longer available");
    }
}
