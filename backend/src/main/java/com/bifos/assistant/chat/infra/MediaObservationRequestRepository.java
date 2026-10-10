package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.MediaObservationRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaObservationRequestRepository extends JpaRepository<MediaObservationRequest, Long> {
    Optional<MediaObservationRequest> findByAttachmentIdAndRequestId(Long attachmentId, String requestId);
}
