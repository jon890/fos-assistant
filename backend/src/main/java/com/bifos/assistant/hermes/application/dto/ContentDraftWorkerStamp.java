package com.bifos.assistant.hermes.application.dto;

import com.bifos.assistant.shared.util.Sha256;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.UUID;

/** ticket 시각과 달리 UTC 소수 여섯 자리를 고정해 private worker와 대조한다. */
public record ContentDraftWorkerStamp(String protocol, UUID snapshotId, UUID jobId, String snapshotHash,
    String bindingId, String connectionId, String bindingUpdatedAt, String connectionUpdatedAt, String profileHash) {
    public static ContentDraftWorkerStamp of(String profile, UUID snapshotId, UUID jobId, String snapshotHash,
        Long bindingId, Long connectionId, Instant bindingUpdatedAt, Instant connectionUpdatedAt) {
        return new ContentDraftWorkerStamp("content-snapshot-v1", snapshotId, jobId, snapshotHash,
            bindingId.toString(), connectionId.toString(), micros(bindingUpdatedAt),
            micros(connectionUpdatedAt), Sha256.hex(profile));
    }


    private static String micros(Instant value) {
        return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);
    }
}
