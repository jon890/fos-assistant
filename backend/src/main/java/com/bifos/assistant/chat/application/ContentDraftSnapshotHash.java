package com.bifos.assistant.chat.application;

import java.util.Comparator;


import com.bifos.assistant.chat.domain.ContentDraftException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Set;
import java.util.Map;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** v1 tuple과 UTF-8 직렬화로 compiler의 응답 전체를 요청과 대조한다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ContentDraftSnapshotHash {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VERSION = "content-compiler-v1";

    public static ObjectNode compile(JsonNode draft, JsonNode assets) {
        if (!assets.isArray() || assets.size() > 50 || bytes(draft).length > 65536) {
            throw unavailable();
        }
        var byId = new HashMap<String, JsonNode>();
        String directory = null;
        for (var asset : assets) {
            String id = asset.path("assetId").stringValue();
            String current = asset.path("photoDir").stringValue();
            if (id == null || byId.put(id, asset) != null || current == null || directory != null && !directory.equals(current)) {
                throw unavailable();
            }
            directory = current;
        }
        var canonical = JSON.createArrayNode();
        var manifest = JSON.createArrayNode();
        var notes = JSON.createObjectNode();
        var lines = new ArrayList<String>();
        int position = 0;
        for (var block : draft.path("blocks")) {
            var tuple = blockTuple(block, position, byId, manifest, notes, lines);
            canonical.add(tuple);
            position++;
        }
        String body = String.join("\n", lines);
        if (body.codePointCount(0, body.length()) > 20000) {
            throw unavailable();
        }
        var payload = JSON.createObjectNode();
        for (String key : Set.of("title", "category", "tags")) {
            payload.set(key, draft.get(key));
        }
        payload.put("body", body);
        if (!manifest.isEmpty()) {
            payload.put("photo_dir", directory);
        }
        var result = JSON.createObjectNode().put("schemaVersion", 1).put("compilerVersion", VERSION);
        result.set("draftId", draft.get("draftId"));
        result.set("revision", draft.get("revision"));
        result.set("canonicalBlocks", canonical);
        result.set("payload", payload);
        result.set("photoNotes", notes);
        result.set("assetManifest", manifest);
        result.put("payloadFingerprint", hash(payloadBytes(result)));
        result.put("snapshotHash", hash(snapshotBytes(result)));
        if (bytes(result).length > 262144) {
            throw unavailable();
        }
        // LongNode와 IntNode의 내부 구분 대신 실제 JSON wire의 정수 표현으로 대조한다.
        return (ObjectNode)JSON.readTree(bytes(result));
    }

    private static ArrayNode blockTuple(JsonNode block, int position, Map<String, JsonNode> byId,
        ArrayNode manifest, ObjectNode notes, List<String> lines) {
        String type = block.path("type").stringValue();
            var tuple = JSON.createArrayNode().add(type);
            switch (type) {
                case "paragraph" -> {
                    tuple.add(block.get("text"));
                    lines.add(block.path("text").stringValue());
                }
                case "image" -> {
                    var asset = byId.get(block.path("assetId").stringValue());
                    if (asset == null || manifest.size() == 50) {
                        throw unavailable();
                    }
                    int number = manifest.size() + 1;
                    tuple.add(block.get("assetId")).add(block.get("caption"));
                    var ref = block.get("observationRef");
                    tuple.add(ref == null ? null : JSON.createArrayNode().add(ref.get("id")).add(ref.get("revision")));
                    var item = JSON.createObjectNode().put("position", position);
                    item.set("assetId", asset.get("assetId"));
                    item.set("sourceFingerprint", asset.get("sourceFingerprint"));
                    item.set("fileName", asset.get("fileName"));
                    manifest.add(item);
                    lines.add("[사진 " + number + ": " + asset.path("fileName").stringValue() + "]");
                    if (block.has("caption")) {
                        lines.add(block.path("caption").stringValue());
                    }
                    if (asset.has("observationNote")) {
                        notes.set(Integer.toString(number), asset.get("observationNote"));
                    }
                }
                case "sticker" -> {
                    tuple.add(block.get("code"));
                    lines.add("[스티커: " + block.path("code").stringValue() + "]");
                }
                case "map" -> {
                    tuple.add(block.get("name")).add(block.get("address"));
                    lines.add("[지도: " + block.path("name").stringValue() + " | " + block.path("address").stringValue() + "]");
                }
                default -> throw unavailable();
            }
        return tuple;
    }

    public static void verify(JsonNode draft, JsonNode assets, JsonNode result) {
        if (result == null || !compile(draft, assets).equals(result)) {
            throw unavailable();
        }
    }

    public static byte[] payloadBytes(JsonNode snapshot) {
        var payload = snapshot.path("payload");
        return bytes(JSON.createArrayNode().add(payload.get("title")).add(payload.get("category"))
            .add(payload.get("tags")).add(payload.get("body")).add(payload.get("photo_dir")));
    }

    public static byte[] snapshotBytes(JsonNode snapshot) {
        var assets = JSON.createArrayNode();
        for (var asset : snapshot.path("assetManifest")) {
            assets.add(JSON.createArrayNode().add(asset.get("position")).add(asset.get("assetId"))
                .add(asset.get("sourceFingerprint")).add(asset.get("fileName")));
        }
        var notes = JSON.createArrayNode();
        snapshot.path("photoNotes").propertyNames().stream().sorted(Comparator.comparingInt(Integer::parseInt))
            .forEach(key -> notes.add(JSON.createArrayNode().add(Integer.parseInt(key)).add(snapshot.path("photoNotes").get(key))));
        return bytes(JSON.createArrayNode().add(snapshot.get("schemaVersion")).add(snapshot.get("compilerVersion"))
            .add(snapshot.get("draftId")).add(snapshot.get("revision")).add(snapshot.get("canonicalBlocks"))
            .add(assets).add(snapshot.get("payloadFingerprint")).add(notes));
    }

    public static byte[] bytes(JsonNode node) {
        return JSON.writeValueAsBytes(node);
    }

    public static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(Sha256.newDigest().digest(bytes));
    }

    public static ContentDraftException unavailable() {
        return new ContentDraftException(ErrorCode.CONTENT_DRAFT_UNAVAILABLE, null);
    }
}
