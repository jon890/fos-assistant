package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.entity.ContentDraftSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentDraftSnapshotRepository extends JpaRepository<ContentDraftSnapshot, Long> {
    Optional<ContentDraftSnapshot> findByPublicIdAndOwnerUserIdAndConversationId(UUID publicId, Long ownerUserId, Long conversationId);


    Optional<ContentDraftSnapshot> findByRevisionIdAndBindingIdAndSnapshotHashAndOwnerUserIdAndConversationId(Long revisionId, Long bindingId, String snapshotHash, Long ownerUserId, Long conversationId);
}
