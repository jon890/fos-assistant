package com.bifos.assistant.chat.contentdraft.application;

import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageSource;
import com.bifos.assistant.chat.attachment.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.attachment.application.DraftImageCopies;
import com.bifos.assistant.chat.attachment.application.dto.ChatContentMutationTarget;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftImageView;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 그 대화에서 만든 그림을 블로그 원고에 넣을 수 있게 사진 첨부 사본으로 꺼낸다(ADR-20261120 / generated-image-draft).
 *
 * <p>같은 그림의 쓸 수 있는 사본이 있으면 그것을 돌려준다. 그림 파일이 지워졌으면 사본이 있어도 거절하고, 이미 넣은 원고는
 * 사본으로 남는다(스펙 generated-image REQ19). 찾기부터 사본 쓰기까지 사용자와 대화 잠금 안에서 해 같은 그림을 동시에 넣어도 사본이 하나다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ContentDraftImages {
    private final ConversationAccess access;
    private final ChatMessageRepository messages;
    private final ChatContentMutationCoordinator mutations;
    private final GeneratedImageService images;
    private final DraftImageCopies copies;


    /**
     * @throws ApiException {@link ErrorCode#IMAGE_SOURCE_UNKNOWN} 은 그 제목의 그림이 없을 때, {@link ErrorCode#IMAGE_SOURCE_GONE} 은
     * 그림 파일이 보관 기간 정리로 지워졌을 때다
     */
    public ContentDraftImageView attach(CurrentUser user, Long conversationId, String title) {
        requireWritable(user, conversationId);
        return mutations.run(user.id(), new ChatContentMutationTarget(conversationId, List.of()), () -> {
            requireWritable(user, conversationId);
            // 그림 파일이 지워졌으면 사본이 있어도 새로 넣지 못한다(스펙 REQ19). 이미 넣은 원고는 사본 번호를 그대로 쓴다.
            Long artifactId = images.liveIdByTitle(conversationId, title);
            // 바이트는 사본을 새로 만들 때만 읽는다. 잠근 트랜잭션 안에서 큰 그림을 매번 읽지 않는다.
            ChatAttachment copy = copies.findUsable(user.id(), conversationId, artifactId).orElseGet(() -> {
                GeneratedImageSource source = images.readByTitle(conversationId, title);
                return copies.create(user.id(), conversationId, artifactId, title, source.contentType(), source.bytes());
            });
            return new ContentDraftImageView(copy.id().toString(), copy.storedName(),
                copies.agentDirectory(user.id(), conversationId), title);
        });
    }


    /** 원고를 쓸 수 없는 대화(살펴보기, 메시지 없는 자동 작업)는 원고 저장과 같이 없는 대화로 답한다. */
    private void requireWritable(CurrentUser user, Long conversationId) {
        var conversation = access.requireOwn(user, conversationId);
        if (conversation.purpose() == ConversationPurpose.CHECK
            || conversation.taskId() != null && !messages.existsByConversationId(conversationId)) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
    }
}
