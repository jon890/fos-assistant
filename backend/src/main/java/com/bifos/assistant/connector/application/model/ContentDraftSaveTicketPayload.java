package com.bifos.assistant.connector.application.model;

import java.time.Instant;
import java.util.UUID;

/** 원고 저장 ticket이 서명하는 칸이다. 본문·경로·비밀값은 담지 않는다. */
public record ContentDraftSaveTicketPayload(
    UUID jobId,
    UUID snapshotId,
    String snapshotHash,
    UUID actionId,
    Long ownerUserId,
    Long conversationId,
    Long bindingId,
    Long connectionId,
    Instant bindingUpdatedAt,
    Instant connectionUpdatedAt,
    Instant issuedAt,
    Instant expiresAt) {
}
