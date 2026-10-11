package com.bifos.assistant.chat.application.model;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CanonicalDraft(int schemaVersion, UUID draftId, long revision, UUID conversationId,
    String title, String category, JsonNode tags, JsonNode blocks) {
}
