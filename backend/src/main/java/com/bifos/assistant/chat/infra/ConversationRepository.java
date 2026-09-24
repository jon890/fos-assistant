package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.Conversation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    List<Conversation> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    @Modifying
    @Transactional
    @Query("update Conversation c set c.hermesSessionId = coalesce(:sessionId, c.hermesSessionId), c.updatedAt = :now where c.id = :id")
    int touchSession(@Param("id") Long id, @Param("sessionId") String sessionId, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update Conversation c set c.title = :title where c.id = :id and c.title = ''")
    int fillTitleIfBlank(@Param("id") Long id, @Param("title") String title);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update Conversation c set c.title = :title, c.updatedAt = :now
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int renameIfActive(@Param("id") Long id, @Param("userId") Long userId,
            @Param("title") String title, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update Conversation c set c.deletedAt = :now
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int deleteIfActive(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);
}
