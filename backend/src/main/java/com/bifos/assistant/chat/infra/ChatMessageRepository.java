package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.type.MessageRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /** 대화에서 그 역할이 처음 남긴 메시지를 읽는다. */
    Optional<ChatMessage> findFirstByConversationIdAndRoleOrderByIdAsc(Long conversationId, MessageRole role);

    /** 그 실행이 남긴 메시지가 이미 있는지 본다. */
    boolean existsByExecutionId(Long executionId);
}
