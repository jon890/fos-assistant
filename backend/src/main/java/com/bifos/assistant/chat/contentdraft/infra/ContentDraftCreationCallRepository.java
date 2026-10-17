package com.bifos.assistant.chat.contentdraft.infra;

import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftCreationCall;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentDraftCreationCallRepository extends JpaRepository<ContentDraftCreationCall, Long> {
    Optional<ContentDraftCreationCall> findByCallKey(byte[] key);


    Optional<ContentDraftCreationCall> findByIdentityKey(byte[] key);
}
