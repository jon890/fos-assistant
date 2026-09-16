package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.Conversation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByIdAndUserId(Long id, Long userId);

    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
