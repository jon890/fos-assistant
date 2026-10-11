package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.entity.ContentDraftCreationRequest;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentDraftCreationRequestRepository extends JpaRepository<ContentDraftCreationRequest, Long> {
    Optional<ContentDraftCreationRequest> findByCreationKey(byte[] key);

    Optional<ContentDraftCreationRequest> findByIdentityKey(byte[] key);

    List<ContentDraftCreationRequest> findByConversationIdOrderByIdAsc(Long conversationId);

    List<ContentDraftCreationRequest> findByDraftPublicId(String draftPublicId);
}
