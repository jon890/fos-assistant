package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.entity.ContentDraftSnapshot;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentDraftSnapshotRepository extends JpaRepository<ContentDraftSnapshot, Long> {
    Optional<ContentDraftSnapshot> findByPublicIdAndOwnerUserIdAndConversationId(UUID publicId, Long ownerUserId, Long conversationId);


    Optional<ContentDraftSnapshot> findByPublicIdAndOwnerUserId(UUID publicId, Long ownerUserId);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ContentDraftSnapshot s where s.id = :id")
    Optional<ContentDraftSnapshot> lockById(@Param("id") Long id);


    Optional<ContentDraftSnapshot> findByRevisionIdAndBindingIdAndSnapshotHashAndAuthorizationFingerprintAndOwnerUserIdAndConversationId(Long revisionId, Long bindingId, String snapshotHash, String authorizationFingerprint, Long ownerUserId, Long conversationId);
}
