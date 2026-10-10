package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.ConnectorTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 카탈로그 JSON을 검증하고 항목의 {@code tools} 칸을 도구 정책으로 읽는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorCatalogTools {
    private static final JsonMapper CATALOG = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** tree 변환 전에 중복과 뒤따르는 JSON을 거절하며 원문과 cause를 오류 처리에 넘기지 않는다. */
    static JsonNode parseCatalog(String raw) {
        try {
            JsonNode catalog = CATALOG.readTree(raw);
            if (!catalog.isArray()) {
                throw new IllegalStateException();
            }
            return catalog;
        } catch (RuntimeException ignored) {
            throw new IllegalStateException();
        }
    }

    static boolean readable(JsonNode declared) {
        return declared == null || declared.isNull() || declared.isObject();
    }

    /**
     * 도구 이름을 키로 하는 객체를 읽는다. 옛 대시보드 plugin 은 이 칸을 내지 않고, 없으면 빈 목록이다.
     *
     * <p>위험도와 승인 방식은 글자 그대로 담고 없으면 null 로 둔다. 뜻을 읽고 거르는 것은 부르는 쪽이 한다. 여기서
     * 거절하면 선언이 틀린 커넥터 하나 때문에 카탈로그 전체를 읽지 못한다. 객체가 아닌 값은 부르는 쪽이 먼저 거른다.
     */
    static List<ConnectorTool> read(JsonNode declared) {
        if (declared == null || declared.isNull()) {
            return List.of();
        }
        List<ConnectorTool> tools = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : declared.properties()) {
            JsonNode policy = entry.getValue();
            tools.add(new ConnectorTool(
                    entry.getKey(),
                    text(policy, "risk"),
                    text(policy, "approval"),
                    text(policy, "title"),
                    grant(policy),
                    identifiers(policy)));
        }
        return List.copyOf(tools);
    }

    /**
     * 상시 허락을 줄 수 있는지의 선언이다(ADR-065). 칸이 없으면 null 이고 받는 쪽이 승인 방식으로 정한다.
     *
     * <p>boolean 이 아닌 값은 거절하지 않고 거짓으로 읽는다. 형식은 대시보드 plugin 이 검사하고, 여기서는 읽을 수 없는
     * 선언이 상시 허락을 여는 쪽으로 읽히지 않게만 한다.
     */
    private static Boolean grant(JsonNode policy) {
        JsonNode value = policy == null ? null : policy.get("grant");
        if (value == null) {
            return null;
        }
        return value.isBoolean() && value.asBoolean();
    }

    /**
     * 식별자 인자의 선언이다(ADR-089). 칸이 없으면 빈 목록이다.
     *
     * <p>문자열 배열이 아니면 거절하지 않고 빈 목록으로 읽는다. 형식은 대시보드 plugin 이 검사하고, 여기서는 읽을 수
     * 없는 선언이 가림을 푸는 쪽으로 읽히지 않게만 한다.
     */
    private static List<String> identifiers(JsonNode policy) {
        JsonNode value = policy == null ? null : policy.get("identifiers");
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> identifiers = new ArrayList<>();
        for (JsonNode name : value) {
            if (!name.isString()) {
                return List.of();
            }
            identifiers.add(name.asString());
        }
        return List.copyOf(identifiers);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
