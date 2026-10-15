package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import java.time.Instant;
import java.util.UUID;

/** Control Plane이 보관한 원고 저장 job 상태다. 본문·ticket·경로는 담지 않는다. */
public record ContentDraftJobView(UUID snapshotId, UUID draftId, long revision, UUID actionId, UUID jobId,
    ContentDraftJobStatus status, String stage, boolean saveClicked, Instant lastCheckedAt, String errorCode,
    String errorReason) {
}
