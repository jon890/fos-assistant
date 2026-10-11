package com.bifos.assistant.chat.application.model;

import java.time.Instant;

public record ContentDraftView(CanonicalDraft draft, String status, Instant expiresAt) {
}
