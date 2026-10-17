package com.bifos.assistant.chat.contentdraft.application.dto;

import java.time.Instant;

public record ContentDraftView(CanonicalDraft draft, String status, Instant expiresAt) {
}
