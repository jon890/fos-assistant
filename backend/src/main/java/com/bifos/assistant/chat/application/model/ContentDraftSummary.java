package com.bifos.assistant.chat.application.model;

import java.time.Instant;
import java.util.UUID;

public record ContentDraftSummary(UUID draftId, long revision, String title, String status, Instant expiresAt) {}
