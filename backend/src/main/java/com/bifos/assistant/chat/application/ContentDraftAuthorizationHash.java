package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ContentDraftSnapshotInput;
import com.bifos.assistant.shared.util.Sha256;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 서버의 캡처 값을 고정 순서 UTF-8 tuple로 구별한다. 서명이나 현재 권한 검사를 대신하지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ContentDraftAuthorizationHash {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter MICRO = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    public static String fingerprint(Long ownerUserId, Long conversationId, ContentDraftSnapshotInput input) {
        var stamp = input.binding();
        var tuple = JSON.createArrayNode().add(1).add("content-draft-preview-authorization")
            .add(ownerUserId).add(conversationId).add(input.revisionId()).add(input.originExecutionId())
            .add(stamp.agentId()).add(stamp.connectionId()).add(stamp.bindingId())
            .add(MICRO.format(stamp.connectionUpdatedAt().truncatedTo(ChronoUnit.MICROS)))
            .add(MICRO.format(stamp.bindingUpdatedAt().truncatedTo(ChronoUnit.MICROS)))
            .add(Sha256.hex(stamp.profile())).add(stamp.connectorId()).add(stamp.policyFingerprint());
        return ContentDraftSnapshotHash.hash(ContentDraftSnapshotHash.bytes(tuple));
    }
}
