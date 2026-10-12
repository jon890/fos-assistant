package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.entity.ContentDraftJob;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentDraftJobRepository extends JpaRepository<ContentDraftJob, Long> {
    Optional<ContentDraftJob> findBySnapshotId(Long snapshotId);
}
