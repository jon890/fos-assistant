package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.FeedbackConversations;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 판단 피드백 기록기가 대화 저장소를 직접 쓰지 않도록 대화가 남아 있는지 답한다.
 *
 * <p>기록기의 트랜잭션 안에서 대화 줄을 공유 잠금으로 읽는다. 대화 삭제({@code ChatService.delete})는 그 줄을 고치고 같은 트랜잭션에서
 * 사건을 지우므로, 삭제가 먼저면 지운 대화로 보고 기록이 먼저면 삭제가 그 사건까지 지운다.
 */
@Service
@RequiredArgsConstructor
public class FeedbackConversationLookup implements FeedbackConversations {

    private final ConversationRepository conversations;

    @Override
    public boolean isActive(Long conversationId) {
        return conversations
                .findByIdForShare(conversationId)
                .filter(conversation -> conversation.deletedAt() == null)
                .isPresent();
    }
}
