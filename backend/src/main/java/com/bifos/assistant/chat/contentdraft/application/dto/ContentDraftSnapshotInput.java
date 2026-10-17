package com.bifos.assistant.chat.contentdraft.application.dto;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 서버 전용 입력이다. 진단 문자열에는 본문과 원본 경로를 싣지 않는다. */
public record ContentDraftSnapshotInput(UUID snapshotId, Long revisionId, Long originExecutionId,
    ContentDraftBindingStamp binding, JsonNode canonicalDraft, JsonNode resolvedAssets, Instant expiresAt) {
    public ContentDraftSnapshotInput {
        canonicalDraft = canonicalDraft.deepCopy();
        resolvedAssets = resolvedAssets.deepCopy();
    }


    @Override
    public JsonNode canonicalDraft() {
        return canonicalDraft.deepCopy();
    }


    @Override
    public JsonNode resolvedAssets() {
        return resolvedAssets.deepCopy();
    }


    @Override
    public String toString() {
        return "ContentDraftSnapshotInput[redacted]";
    }
}
