package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ContentDraftInput;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 순수 compiler의 입력 부분집합만 수용한다. 서버 필드와 문자열 coercion은 받지 않는다. */
@Component
public class ContentDraftInputReader {
    public static final long MAX_REVISION = 9_007_199_254_740_991L;
    /** 같은 사진 중복의 응답 문구다. MCP 결과도 이 상수만 싣고 예외 message 는 싣지 않는다. */
    public static final String DUPLICATE_PHOTO_MESSAGE = "같은 사진은 원고에 한 번만 넣을 수 있습니다. 중복된 사진 블록을 지우고 다시 저장해 주세요.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TRIM = Pattern.compile("^[\\x09-\\x0d\\x20\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+|[\\x09-\\x0d\\x20\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+$");
    private static final List<Pattern> RESERVED = List.of(
        Pattern.compile("^\\[사진 ([1-9][0-9]{0,2}): ([^/\\]]+)\\]$"),
        Pattern.compile("^\\[스티커: ([A-Za-z0-9_-]{1,64})\\]$"),
        Pattern.compile("^\\[지도: ([^|\\]]+?) \\| ([^\\]]+)\\]$"),
        Pattern.compile("^\\[기존 (사진|스티커|지도|구성요소) ([1-9][0-9]*)(?:: [A-Za-z0-9_-]{1,40})?\\]$"));


    public ContentDraftInput read(JsonNode node) {
        fields(node, Set.of("title", "category", "tags", "blocks"), Set.of());
        String title = text(node.get("title"), 1, 100);
        String category = text(node.get("category"), 1, 50);
        var tags = node.get("tags");
        validateTags(tags);
        var blocks = node.get("blocks");
        array(blocks, 500);
        int images = 0;
        int stickers = 0;
        int maps = 0;
        for (var block : blocks) {
            String type = text(block.get("type"), 1, 20);
            switch (type) {
                case "paragraph" -> {
                    fields(block, Set.of("type", "text"), Set.of());
                    line(block.get("text"), 20_000);
                }
                case "image" -> {
                    fields(block, Set.of("type", "assetId"), Set.of("caption", "observationRef"));
                    id(block.get("assetId"));
                    if (block.has("caption")) {
                        line(block.get("caption"), 300);
                    }
                    if (block.has("observationRef")) {
                        var ref = block.get("observationRef");
                        fields(ref, Set.of("id", "revision"), Set.of());
                        id(ref.get("id"));
                        revision(ref.get("revision"));
                    }
                    images++;
                }
                case "sticker" -> {
                    fields(block, Set.of("type", "code"), Set.of());
                    if (!text(block.get("code"), 1, 64).matches("[A-Za-z0-9_-]{1,64}")) {
                        throw invalid();
                    }
                    stickers++;
                }
                case "map" -> {
                    fields(block, Set.of("type", "name", "address"), Set.of());
                    if (text(block.get("name"), 1, 200).matches("(?s).*[\\r\\n\\]|].*")
                        || text(block.get("address"), 1, 200).matches("(?s).*[\\r\\n\\]].*")) {
                        throw invalid();
                    }
                    maps++;
                }
                default -> throw invalid();
            }
        }
        if (images > 50 || stickers > 30 || maps > 30
            || JSON.writeValueAsBytes(node).length > 65_536) {
            throw invalid();
        }
        return new ContentDraftInput(title, category, tags.deepCopy(), blocks.deepCopy());
    }


    /**
     * 새로 쓰는 원고의 이미지 블록이 같은 assetId 를 두 번 쓰면 거절한다.
     *
     * <p>{@link #read} 는 저장한 판을 다시 읽을 때도 쓰므로 이 규칙을 넣지 않는다. 규칙 전에 저장한 판도 읽고 고칠 수 있어야 한다.
     */
    public static void requireUniquePhotos(ContentDraftInput input) {
        Set<String> seen = new HashSet<>();
        for (var block : input.blocks()) {
            if ("image".equals(block.path("type").asString()) && !seen.add(block.path("assetId").asString())) {
                throw new ApiException(ErrorCode.CONTENT_DRAFT_DUPLICATE_PHOTO, DUPLICATE_PHOTO_MESSAGE);
            }
        }
    }


    private static void validateTags(JsonNode tags) {
        array(tags, 30);
        Set<String> unique = new HashSet<>();
        for (var tag : tags) {
            String value = text(tag, 1, 30);
            if (value.contains("#") || value.contains(",") || !unique.add(TRIM.matcher(value).replaceAll("").toLowerCase(Locale.ROOT))) {
                throw invalid();
            }
        }
    }


    public static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        for (String field : required) {
            if (!node.has(field)) {
                throw invalid();
            }
        }
        for (var property : node.properties()) {
            if ((!required.contains(property.getKey()) && !optional.contains(property.getKey()))
                || property.getValue().isNull()) {
                throw invalid();
            }
        }
    }


    public static long revision(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid();
        }
        long value = node.longValue();
        if (value < 1 || value > MAX_REVISION) {
            throw invalid();
        }
        return value;
    }


    public static Long id(JsonNode node) {
        String value = text(node, 1, 19);
        try {
            if (!value.matches("[1-9][0-9]*")) {
                throw invalid();
            }
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }


    public static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) {
                throw invalid();
            }
            return id;
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }


    private static String text(JsonNode node, int min, int max) {
        if (node == null || !node.isString()) {
            throw invalid();
        }
        String value = node.stringValue();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid();
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw invalid();
            }
        }
        int count = value.codePointCount(0, value.length());
        if (count < min || count > max) {
            throw invalid();
        }
        return value;
    }


    private static void line(JsonNode node, int max) {
        String value = text(node, 0, max);
        if (value.contains("\r") || value.contains("\n")
            || RESERVED.stream().anyMatch(pattern -> pattern.matcher(value).matches())) {
            throw invalid();
        }
    }


    private static void array(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.size() > max) {
            throw invalid();
        }
    }


    public static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "원고 입력 형식이 올바르지 않습니다.");
    }
}
