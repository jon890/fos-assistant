package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 콘텐츠 변경은 데이터 주인의 사용자, 대화, 첨부 순서로 잠근다. */
@Component
@RequiredArgsConstructor
public class ChatContentMutationCoordinator {
    private final AppUserRepository users;
    private final ConversationRepository conversations;
    private final ChatAttachmentRepository attachments;
    private final PlatformTransactionManager transactions;
    private final ThreadLocal<ChatContentMutationTarget> entered = new ThreadLocal<>();

    public <T> T run(Long ownerUserId, ChatContentMutationTarget target, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("content mutation must start outside a transaction");
        }
        var transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            users.findByIdForUpdate(ownerUserId).orElseThrow(ChatContentMutationCoordinator::notFound);
            var conversation = conversations
                    .findByIdForMessageWrite(target.conversationId())
                    .orElseThrow(ChatContentMutationCoordinator::notFound);
            if (!ownerUserId.equals(conversation.userId())) {
                throw notFound();
            }
            var ids = target.attachmentIds().isEmpty()
                    ? attachments.findByConversationIdOrderByIdAsc(target.conversationId()).stream()
                            .map(it -> it.id())
                            .toList()
                    : target.attachmentIds();
            for (Long id : ids) {
                attachments
                        .findForMutation(id, target.conversationId())
                        .filter(it -> ownerUserId.equals(it.uploadedByUserId()))
                        .orElseThrow(ChatContentMutationCoordinator::notFound);
            }
            entered.set(target);
            try {
                return work.get();
            } finally {
                entered.remove();
            }
        });
    }

    public void requireParticipant(Long conversationId) {
        if (entered.get() == null || !conversationId.equals(entered.get().conversationId())) {
            throw new IllegalStateException("content writer requires the mutation coordinator");
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
    }
}
