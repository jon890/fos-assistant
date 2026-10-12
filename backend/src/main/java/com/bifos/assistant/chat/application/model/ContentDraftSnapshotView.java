package com.bifos.assistant.chat.application.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** canonical preview만 공개한다. compiler payload와 서버 경로는 포함하지 않는다. */
public record ContentDraftSnapshotView(UUID snapshotId, UUID draftId, long revision, String snapshotHash,
    CanonicalDraft preview, List<String> assetIds, List<String> omittedAssetIds, Instant expiresAt) {
    public ContentDraftSnapshotView {
        assetIds = List.copyOf(assetIds);
        omittedAssetIds = List.copyOf(omittedAssetIds);
    }
}
