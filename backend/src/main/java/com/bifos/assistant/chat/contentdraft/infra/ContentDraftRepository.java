package com.bifos.assistant.chat.contentdraft.infra;

import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraft;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ContentDraftRepository extends JpaRepository<ContentDraft, Long> {
    Optional<ContentDraft> findByPublicIdAndConversationIdAndOwnerUserId(String publicId, Long conversationId, Long ownerUserId);


    List<ContentDraft> findTop100ByConversationIdOrderByCreatedAtDescIdDesc(Long conversationId);


    List<ContentDraft> findByConversationIdOrderByIdAsc(Long conversationId);


    @Modifying(flushAutomatically = true)
    @Query("update ContentDraft d set d.currentRevision = :next, d.expiresAt = :expiresAt where d.id = :id and d.currentRevision = :expected")
    int advance(Long id, long expected, long next, Instant expiresAt);


    @Modifying(flushAutomatically = true)
    @Query("delete from ContentDraft d where d.conversationId = :conversationId")
    int deleteForConversation(Long conversationId);
}
