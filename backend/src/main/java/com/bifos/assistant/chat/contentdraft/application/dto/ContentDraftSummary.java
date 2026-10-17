package com.bifos.assistant.chat.contentdraft.application.dto;

import java.time.Instant;
import java.util.UUID;

public record ContentDraftSummary(UUID draftId, long revision, String title, String status, Instant expiresAt) {
}
