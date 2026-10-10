package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.MediaObservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MediaObservationRepository extends JpaRepository<MediaObservation, Long> {
    Optional<MediaObservation> findFirstByAttachmentIdOrderByRevisionDesc(Long attachmentId);

    @Modifying(flushAutomatically = true)
    @Query("delete from MediaObservation o where o.conversationId = :conversationId and o.attachmentId = :attachmentId")
    int deleteForAttachment(Long conversationId, Long attachmentId);

    @Modifying(flushAutomatically = true)
    @Query("delete from MediaObservation o where o.conversationId = :conversationId")
    int deleteForConversation(Long conversationId);

    /** 숫자 ID와 최신 주인만 읽는다. 본문은 정리 후보에 싣지 않는다. */
    @Query("""
            select distinct o.conversationId, o.attachmentId, c.userId
              from MediaObservation o join ChatAttachment a on a.id = o.attachmentId
              join Conversation c on c.id = o.conversationId
             where o.expiresAt <= :now or a.expiresAt <= :now or c.deletedAt is not null
                or a.deletionRequestedAt is not null or a.deletedAt is not null
                or o.ownerUserId <> c.userId or o.ownerUserId <> a.uploadedByUserId
             order by o.conversationId, o.attachmentId
            """)
    List<Object[]> findCleanupCandidates(Instant now);
}
