package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
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
                .orElseThrow(
                        () ->
                                new ApiException(
                                        ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist"));
    }
}
