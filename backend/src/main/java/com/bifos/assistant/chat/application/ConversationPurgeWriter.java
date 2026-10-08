package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ExecutionQuestionRepository;
import com.bifos.assistant.usage.application.ConversationExecutionPurge;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지운 대화 하나의 본문을 데이터베이스에서 한 트랜잭션으로 지운다(ADR-20261008 / conversation-purge).
 *
 * <p>대화 줄을 메시지 저장과 같은 쓰기 잠금으로 잡는다. 늦게 끝난 실행이 메시지를 남기는 중이면 그 커밋을 기다리고, 먼저 잡으면
 * 그 저장이 이 커밋 뒤에 정리된 대화를 보고 거절된다. 파일과 Hermes session 은 부르는 {@link ConversationPurger} 가 먼저 지운다.
 */
@Component
@RequiredArgsConstructor
class ConversationPurgeWriter {

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatPendingMessageRepository pendingMessages;
    private final ChatAttachmentRepository attachments;
    private final ChatArtifactRepository artifacts;
    private final ExecutionQuestionRepository questions;
    private final ConversationExecutionPurge executions;

    /**
     * 그 대화의 본문을 지우고 지운 시각을 적는다.
     *
     * @return 지웠으면 참. 이미 정리했거나 지운 대화가 아니면 거짓
     */
    @Transactional
    public boolean purge(Long conversationId, Instant now) {
        Conversation conversation =
                conversations.findByIdForMessageWrite(conversationId).orElse(null);
        if (conversation == null || conversation.deletedAt() == null || conversation.purgedAt() != null) {
            return false;
        }
        pendingMessages.deleteAllOf(conversationId);
        attachments.deleteAllOf(conversationId);
        artifacts.deleteAllOf(conversationId);
        questions.deleteAllOf(conversationId);
        messages.clearReplacesOf(conversationId);
        messages.deleteAllOf(conversationId);
        executions.eraseBodies(conversationId);
        return conversations.markPurged(conversationId, now) == 1;
    }
}
