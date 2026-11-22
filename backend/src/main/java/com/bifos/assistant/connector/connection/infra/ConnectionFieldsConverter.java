package com.bifos.assistant.connector.connection.infra;

import com.bifos.assistant.connector.connection.domain.ConnectionAuth;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ConnectionFields} 를 JSON 텍스트 열로 읽고 쓴다.
 *
 * <p>MySQL {@code JSON} 타입을 쓰지 않는 까닭은 {@code backend/docs/data-schema.md} 의 「connector_connection」 에 있다.
 * {@code domain} 이 {@code infra} 를 가리키지 않도록 그 타입의 모든 칸에 자동으로 적용한다.
 */
@Converter(autoApply = true)
public class ConnectionFieldsConverter implements AttributeConverter<ConnectionFields, String> {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String VALUES = "values";
    private static final String SECRET_PREFIXES = "secretPrefixes";
    private static final String AUTH = "auth";
    private static final String METHOD = "method";
    private static final String PROVIDER = "provider";
    private static final String RECONNECT_REQUIRED = "reconnectRequired";


    @Override
    public String convertToDatabaseColumn(ConnectionFields attribute) {
        ConnectionFields fields = attribute == null ? ConnectionFields.empty() : attribute;
        ObjectNode root = MAPPER.createObjectNode();
        fields.values().forEach(root.putObject(VALUES)::put);
        fields.secretPrefixes().forEach(root.putObject(SECRET_PREFIXES)::put);
        // 수동 연결은 옛 모양을 쓰므로 이미 열의 최대 길이에 이른 행도 계속 저장할 수 있다.
        if (fields.auth().usesOAuth()) {
            root.putObject(AUTH).put(METHOD, "oauth").put(PROVIDER, fields.auth().provider())
                .put(RECONNECT_REQUIRED, fields.auth().reconnectRequired());
        }
        return MAPPER.writeValueAsString(root);
    }


    @Override
    public ConnectionFields convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return ConnectionFields.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(column);
            return new ConnectionFields(texts(root.get(VALUES)), texts(root.get(SECRET_PREFIXES)), auth(root.get(AUTH)));
        } catch (JacksonException ex) {
            // 열의 원문에는 사용자가 넣은 값이 있으므로 예외에 싣지 않는다.
            throw new IllegalStateException("connector_connection.fields is not valid JSON");
        }
    }


    private static ConnectionAuth auth(JsonNode node) {
        if (node == null || node.isNull() || "manual".equals(node.path(METHOD).stringValue())) {
            return ConnectionAuth.MANUAL;
        }
        String provider = node.path(PROVIDER).stringValue();
        JsonNode reconnect = node.path(RECONNECT_REQUIRED);
        if (!"oauth".equals(node.path(METHOD).stringValue()) || provider == null || provider.isBlank() || !reconnect.isBoolean()) {
            throw new IllegalStateException("connector_connection.fields.auth has an unexpected shape");
        }
        return new ConnectionAuth(provider, reconnect.booleanValue());
    }


    private static Map<String, String> texts(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (!node.isObject()) {
            throw new IllegalStateException("connector_connection.fields has an unexpected shape");
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!entry.getValue().isString()) {
                throw new IllegalStateException("connector_connection.fields has an unexpected shape");
            }
            result.put(entry.getKey(), entry.getValue().asString());
        }
        return result;
    }
}
