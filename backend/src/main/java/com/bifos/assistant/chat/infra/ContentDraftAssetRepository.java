package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.entity.ContentDraftAsset;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ContentDraftAssetRepository extends JpaRepository<ContentDraftAsset, Long> {
    List<ContentDraftAsset> findByRevisionIdOrderByPositionAsc(Long revisionId);
    @Modifying(flushAutomatically = true)
    @Query("delete from ContentDraftAsset a where a.revisionId in (select r.id from ContentDraftRevision r where r.draftId in (select d.id from ContentDraft d where d.conversationId = :conversationId))")
    int deleteForConversation(Long conversationId);
}

