package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import java.util.UUID;

/** 저장 요청의 승인 줄과 예약 job이다. jobId는 승인 전에는 null이다. */
public record ContentDraftSaveRequestView(UUID snapshotId, UUID actionId, UUID jobId, ContentDraftJobStatus status) {
}
