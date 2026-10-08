package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 카탈로그 응답의 항목 하나를 {@link ConnectorManifest} 로 읽는다.
 *
 * <p>틀린 필수 칸은 {@link IllegalStateException} 으로 알리고, 응답 칸을 읽는 도우미는 {@link HttpHermesConnectorClient}
 * 의 다른 응답에도 함께 쓴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorCatalogParser {
    private static final int SCHEMA_WITHOUT_TOOLS = 1;
    /** 읽을 수 없는 선언에 주는 판이다. 받는 쪽이 아는 판이 아니라 그 커넥터만 카탈로그에서 빠진다. */
    private static final int SCHEMA_UNREADABLE = 0;

    static ConnectorManifest read(JsonNode item) {
        JsonNode declared = item.get("fields");
        if (declared == null || !declared.isArray()) {
            throw new IllegalStateException();
        }
        List<ConnectorField> fields = new ArrayList<>();
        for (JsonNode field : declared) {
            fields.add(field(field));
        }
        JsonNode schema = item.get("schema");
        JsonNode tools = item.get("tools");
        // 판이나 도구 선언의 모양이 틀리면 둘을 함께 버린다. 도구만 비우면 도구를 선언하지 않는 판으로 읽혀 통과한다.
        boolean readable = readableSchema(schema) && readableTools(tools);
        return new ConnectorManifest(
                requiredText(item, "id"),
                requiredText(item, "title"),
                text(item, "description"),
                fields,
                requiredText(item.get("verify"), "tool"),
                requiredText(item, "mcp_server"),
                names(item.get("toolsets")),
                optionalBoolean(item, "attachments", false),
                readable ? schema(schema) : SCHEMA_UNREADABLE,
                readable ? tools(tools) : List.of(),
                names(item.get("skills")),
                ConnectorAppearances.read(item),
                optionalBoolean(item, "owner_browser", false),
                loginUrl(item));
    }

    /** 로그인 안내 주소다. 문자열이고 {@code https://} 로 시작할 때만 읽고, 아니면 null 이다. 카탈로그 전체를 버리지 않는다. */
    private static String loginUrl(JsonNode item) {
        String value = text(item, "owner_browser_login_url");
        return value != null && value.startsWith("https://") ? value : null;
    }

    private static boolean readableSchema(JsonNode declared) {
        return declared == null || declared.isNull() || declared.isInt();
    }

    private static boolean readableTools(JsonNode declared) {
        return declared == null || declared.isNull() || declared.isObject();
    }

    /** 옛 대시보드 plugin 은 이 칸을 내지 않는다. 없으면 도구를 선언하지 않는 판이다. */
    private static int schema(JsonNode declared) {
        return declared == null || declared.isNull() ? SCHEMA_WITHOUT_TOOLS : declared.asInt();
    }

    /**
     * 도구 이름을 키로 하는 객체를 읽는다. 옛 대시보드 plugin 은 이 칸을 내지 않고, 없으면 빈 목록이다.
     *
     * <p>위험도와 승인 방식은 글자 그대로 담고 없으면 null 로 둔다. 뜻을 읽고 거르는 것은 부르는 쪽이 한다. 여기서
     * 거절하면 선언이 틀린 커넥터 하나 때문에 카탈로그 전체를 읽지 못한다. 객체가 아닌 값은 부르는 쪽이 먼저 거른다.
     */
    private static List<ConnectorTool> tools(JsonNode declared) {
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

    /** 이름 목록 칸({@code toolsets}, {@code skills})을 읽는다. 옛 대시보드 plugin 은 이 칸을 내지 않고, 없으면 빈 목록이다. */
    private static List<String> names(JsonNode declared) {
        if (declared == null || declared.isNull()) {
            return List.of();
        }
        if (!declared.isArray()) {
            throw new IllegalStateException();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode name : declared) {
            if (!name.isString() || name.asString().isBlank()) {
                throw new IllegalStateException();
            }
            names.add(name.asString());
        }
        return List.copyOf(names);
    }

    /** manifest 에서 생략할 수 있는 칸의 기본값은 {@code docs/connectors.md} 의 「connector.json」 과 같다. */
    private static ConnectorField field(JsonNode field) {
        String key = requiredText(field, "key");
        String label = text(field, "label");
        String description = text(field, "description");
        return new ConnectorField(
                key,
                requiredText(field, "env"),
                label == null ? key : label,
                description == null ? "" : description,
                optionalBoolean(field, "secret", false),
                optionalBoolean(field, "required", true),
                text(field, "pattern"),
                options(field.get("options")));
    }

    private static ConnectorFieldOptions options(JsonNode options) {
        if (options == null || options.isNull()) {
            return null;
        }
        return new ConnectorFieldOptions(
                requiredText(options, "tool"),
                requiredText(options, "items"),
                requiredText(options, "value"),
                requiredText(options, "label"),
                optionalBoolean(options, "auto_select_single", false));
    }

    static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException();
        }
        return value.asBoolean();
    }

    static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        // 칸이 있으면 필수 칸과 같게 읽는다. 불리언이 아니면 실패다.
        return value == null || value.isNull() ? fallback : requiredBoolean(node, field);
    }

    static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalStateException();
        }
        return value;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
