package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.entity.ContentDraftRevision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ContentDraftRevisionRepository extends JpaRepository<ContentDraftRevision, Long> {
    Optional<ContentDraftRevision> findByDraftIdAndRevision(Long draftId, long revision);

    List<ContentDraftRevision> findByDraftIdOrderByRevisionAsc(Long draftId);

    @Modifying(flushAutomatically = true)
    @Query("update ContentDraftRevision r set r.status = 'STALE', r.body = null, r.bodyKeyId = null where r.status = 'VALID' and r.draftId = :draftId")
    int invalidateDraft(Long draftId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ContentDraftRevision r where r.draftId in (select d.id from ContentDraft d where d.conversationId = :conversationId)")
    int deleteForConversation(Long conversationId);

    @Modifying(flushAutomatically = true)
    @Query("update ContentDraftRevision r set r.status = 'STALE', r.body = null, r.bodyKeyId = null where r.status = 'VALID' and r.id in (select a.revisionId from ContentDraftAsset a where a.attachmentId = :attachmentId)")
    int invalidateAttachment(Long attachmentId);

    @Modifying(flushAutomatically = true)
    @Query("update ContentDraftRevision r set r.status = 'STALE', r.body = null, r.bodyKeyId = null where r.status = 'VALID' and r.draftId in (select d.id from ContentDraft d where d.conversationId = :conversationId)")
    int invalidateConversation(Long conversationId);

    @Modifying(flushAutomatically = true)
    @Query("update ContentDraftRevision r set r.status = 'STALE', r.body = null, r.bodyKeyId = null where r.status = 'VALID' and r.id = :id")
    int invalidate(Long id);

    @Modifying(flushAutomatically = true)
    @Query("update ContentDraftRevision r set r.status = 'EXPIRED', r.body = null, r.bodyKeyId = null where r.status <> 'EXPIRED' and r.draftId = :draftId")
    int expire(Long draftId);

    /** 후보에는 식별자만 싣고 현재 대화 주인으로 잠금을 잡는다. */
    @Query("""
        select distinct d.conversationId, c.userId from ContentDraft d
          join Conversation c on c.id = d.conversationId
          join ContentDraftRevision r on r.draftId = d.id
         where (r.status <> 'EXPIRED' and d.expiresAt <= :now)
            or (r.status = 'VALID' and (c.deletedAt is not null or d.ownerUserId <> c.userId
            or exists (select a.id from ContentDraftAsset a left join ChatAttachment t on t.id = a.attachmentId
                left join MediaObservation o on o.id = a.observationId
                where a.revisionId = r.id and (t.id is null or t.conversationId <> d.conversationId
                or t.uploadedByUserId <> d.ownerUserId or t.messageId is null or t.deletedAt is not null
                or t.deletionRequestedAt is not null or t.expiresAt <= :now
                or (a.observationId is not null and (o.id is null or o.attachmentId <> a.attachmentId
                or o.conversationId <> d.conversationId or o.ownerUserId <> d.ownerUserId
                or o.revision <> a.observationRevision or o.sourceFingerprint <> a.sourceFingerprint
                or o.expiresAt <= :now or o.body is null or o.status not in ('SUCCEEDED', 'PARTIAL', 'NEEDS_REVIEW')))))))
        """)
    List<Object[]> findCleanupCandidates(Instant now);
}
