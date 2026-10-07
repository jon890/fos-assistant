package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.FeedbackConversations;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 판단 피드백 기록기가 대화 저장소를 직접 쓰지 않도록 대화가 남아 있는지 답한다. */
@Service
@RequiredArgsConstructor
public class FeedbackConversationLookup implements FeedbackConversations {

    private final ConversationRepository conversations;

    @Override
    public boolean isActive(Long conversationId) {
        return conversations
                .findById(conversationId)
                .filter(conversation -> conversation.deletedAt() == null)
                .isPresent();
    }
}
