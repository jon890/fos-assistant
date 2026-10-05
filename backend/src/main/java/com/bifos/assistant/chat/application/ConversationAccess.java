package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 대화의 주인인지 판정한다.
 *
 * <p>대화와 그 첨부가 모두 이 판정 하나를 거친다. 남의 대화는 없는 대화와 같은 응답을 준다.
 */
@Component
@RequiredArgsConstructor
public class ConversationAccess {

    private final ConversationRepository conversations;

    public Conversation requireOwn(CurrentUser user, Long conversationId) {
        return conversations
                .findByIdAndUserIdAndDeletedAtIsNull(conversationId, user.id())
                .orElseThrow(ConversationAccess::notFound);
    }

    /** 사진 upload 동안 장수 확인과 저장을 직렬화할 대화 행을 잠근다. */
    public Conversation requireOwnForUpload(CurrentUser user, Long conversationId) {
        return conversations
                .findActiveByIdAndUserIdForUpload(conversationId, user.id())
                .orElseThrow(ConversationAccess::notFound);
    }

    /** 공개 식별자로 주인의 대화를 찾는다. 번호로 찾을 때와 같은 응답으로 숨긴다. */
    public Conversation requireOwn(CurrentUser user, UUID publicId) {
        return conversations
                .findByPublicIdAndUserIdAndDeletedAtIsNull(publicId, user.id())
                .orElseThrow(ConversationAccess::notFound);
    }

    /** 컨트롤러가 공개 식별자를 서비스가 받는 대화 번호로 바꿀 때 쓴다. */
    public Long requireOwnId(CurrentUser user, UUID publicId) {
        return requireOwn(user, publicId).id();
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
    }
}
