package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.chat.domain.entity.ContentDraftCreationRequest;
import com.bifos.assistant.shared.util.Sha256;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** 서명과 현재 호출자 검사로 얻은 생성 범위다. 모델 인자로 받지 않는다. */
public record ContentDraftCreationContext(Long ownerUserId, Long conversationId, String profileName,
    Long originExecutionId, Long rootExecutionId, String rootSessionId, String sessionId, String toolCallId) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PURPOSE = "CONTENT_DRAFT_CREATE";

    public byte[] creationKey(UUID requestId) {
        var tuple = scope();
        tuple.add(requestId.toString());
        return hash(tuple);
    }

    public byte[] callKey() {
        var tuple = scope();
        tuple.add(sessionId);
        tuple.add(toolCallId);
        return hash(tuple);
    }

    public byte[] creationIdentityKey(UUID requestId) {
        return hash(List.of(1, ownerUserId, conversationId, profileName, PURPOSE, requestId.toString()));
    }

    public byte[] callIdentityKey() {
        return hash(List.of(1, ownerUserId, conversationId, profileName, rootSessionId, PURPOSE, sessionId, toolCallId));
    }

    public boolean matches(ContentDraftCreationRequest row) {
        return ownerUserId.equals(row.ownerUserId()) && conversationId.equals(row.conversationId())
            && profileName.equals(row.profileName()) && originExecutionId.equals(row.originExecutionId())
            && rootExecutionId.equals(row.rootExecutionId()) && rootSessionId.equals(row.rootSessionId());
    }

    public static byte[] requestHash(ContentDraftInput input) {
        var tuples = new ArrayList<Object>();
        for (var block : input.blocks()) {
            switch (block.path("type").stringValue()) {
                case "paragraph" -> tuples.add(List.of("paragraph", block.path("text").stringValue()));
                case "image" -> {
                    var ref = block.get("observationRef");
                    tuples.add(Arrays.asList("image", block.path("assetId").stringValue(),
                        block.has("caption") ? block.get("caption").stringValue() : null,
                        ref == null ? null : List.of(ref.path("id").stringValue(), ref.path("revision").longValue())));
                }
                case "sticker" -> tuples.add(List.of("sticker", block.path("code").stringValue()));
                case "map" -> tuples.add(List.of("map", block.path("name").stringValue(), block.path("address").stringValue()));
                default -> throw new IllegalArgumentException("validated draft required");
            }
        }
        return hash(List.of(1, PURPOSE, input.title(), input.category(), input.tags(), tuples));
    }

    private ArrayList<Object> scope() {
        return new ArrayList<>(List.of(1, ownerUserId, conversationId, profileName, originExecutionId,
            rootExecutionId, rootSessionId, PURPOSE));
    }

    private static byte[] hash(Object tuple) {
        return Sha256.newDigest().digest(JSON.writeValueAsBytes(tuple));
    }

    @Override
    public String toString() {
        return "ContentDraftCreationContext[scope omitted]";
    }
}
