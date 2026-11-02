package com.bifos.assistant.chat.contentdraft.infra;

import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftRevision;
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


    /** 이 대화의 원고에서 아직 유효한 revision 이다. 첨부 하나를 지울 때 그 첨부를 가리키는 줄을 찾는 데 쓴다. */
    @Query("select r from ContentDraftRevision r where r.status = 'VALID' and r.draftId in (select d.id from ContentDraft d where d.conversationId = :conversationId)")
    List<ContentDraftRevision> findValidForConversation(Long conversationId);


    @Modifying(flushAutomatically = true)
    @Query("update ContentDraftRevision r set r.status = 'STALE', r.body = null, r.bodyKeyId = null where r.status = 'VALID' and r.draftId in (select d.id from ContentDraft d where d.conversationId = :conversationId)")
    int invalidateConversation(Long conversationId);


    @Modifying(flushAutomatically = true)
    @Query("update ContentDraftRevision r set r.status = 'STALE', r.body = null, r.bodyKeyId = null where r.status = 'VALID' and r.id = :id")
    int invalidate(Long id);


    @Modifying(flushAutomatically = true)
    @Query("update ContentDraftRevision r set r.status = 'EXPIRED', r.body = null, r.bodyKeyId = null where r.status <> 'EXPIRED' and r.draftId = :draftId")
    int expire(Long draftId);


    /**
     * 후보에는 식별자만 싣고 현재 대화 주인으로 잠금을 잡는다. 사진 참조가 깨진 revision 은 참조가 JSON 칸에 있어 여기서
     * 고르지 않고 {@link #findValidWithDraft} 를 읽은 쪽이 고른다.
     */
    @Query("""
        select distinct d.conversationId, c.userId from ContentDraft d
          join Conversation c on c.id = d.conversationId
          join ContentDraftRevision r on r.draftId = d.id
         where (r.status <> 'EXPIRED' and d.expiresAt <= :now)
            or (r.status = 'VALID' and (c.deletedAt is not null or d.ownerUserId <> c.userId))
        """)
    List<Object[]> findCleanupCandidates(Instant now);


    /**
     * 기한이 남은 원고의 유효한 revision 번호와 사진 참조, 그 원고, 현재 대화 주인이다. 정리가 사진 참조를 확인하는 데 쓴다.
     * 본문은 읽지 않는다.
     */
    @Query("""
        select r.id, r.assets, d, c.userId from ContentDraftRevision r
          join ContentDraft d on d.id = r.draftId
          join Conversation c on c.id = d.conversationId
         where r.status = 'VALID' and d.expiresAt > :now
        """)
    List<Object[]> findValidWithDraft(Instant now);
}
