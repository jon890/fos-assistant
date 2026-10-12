package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorSnapshotDeclaration;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 카탈로그 항목의 선언과 기본값을 읽는다. HTTP 호출과 실행 판정은 client가 맡는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorCatalogDecoder {
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
        boolean readable = readableSchema(schema) && ConnectorCatalogTools.readable(tools);
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
            readable ? ConnectorCatalogTools.read(tools) : List.of(),
            names(item.get("skills")),
            ConnectorAppearances.read(item),
            optionalBoolean(item, "single_binding", false),
            optionalBoolean(item, "owner_browser", false),
            loginUrl(item),
            item.get("execution_guard"),
            text(item, "execution_guard_manifest_sha256"),
            ConnectorSnapshotDeclaration.read(item.get("snapshot"), readable ? ConnectorCatalogTools.read(tools) : List.of()));
    }


    /** 로그인 안내 주소다. 문자열이고 {@code https://} 로 시작할 때만 읽고, 아니면 null 이다. 카탈로그 전체를 버리지 않는다. */
    private static String loginUrl(JsonNode item) {
        String value = text(item, "owner_browser_login_url");
        return value != null && value.startsWith("https://") ? value : null;
    }


    private static boolean readableSchema(JsonNode declared) {
        return declared == null || declared.isNull() || declared.isInt();
    }


    /** 옛 대시보드 plugin 은 이 칸을 내지 않는다. 없으면 도구를 선언하지 않는 판이다. */
    private static int schema(JsonNode declared) {
        return declared == null || declared.isNull() ? SCHEMA_WITHOUT_TOOLS : declared.asInt();
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


    /** manifest 에서 생략할 수 있는 칸의 기본값은 {@code hermes/connectors/README.md} 의 「connector.json」 과 같다. */
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


    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException();
        }
        return value.asBoolean();
    }


    private static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        // 칸이 있으면 필수 칸과 같게 읽는다. 불리언이 아니면 실패다.
        return value == null || value.isNull() ? fallback : requiredBoolean(node, field);
    }


    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalStateException();
        }
        return value;
    }


    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
