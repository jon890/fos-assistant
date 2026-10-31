package com.bifos.assistant.connector.connection.infra;

import com.bifos.assistant.connector.connection.domain.ListedTools;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ListedTools} 를 {@code connector_connection.listed_tools} 의 JSON 텍스트 {@code {"<도구 이름>": <읽기 표시>}} 로 읽고 쓴다.
 *
 * <p>칸이 비어 있으면 null 이다. 표준 플러그인이 아니거나 아직 도구 목록으로 확인하지 않은 연결이다. {@code JSON} 타입을 쓰지 않는
 * 까닭은 {@code fields} 와 같다({@code backend/docs/data-schema.md} 의 「connector_connection」).
 */
@Converter(autoApply = true)
public class ListedToolsConverter implements AttributeConverter<ListedTools, String> {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();


    @Override
    public String convertToDatabaseColumn(ListedTools attribute) {
        if (attribute == null) {
            return null;
        }
        ObjectNode root = MAPPER.createObjectNode();
        attribute.tools().forEach(root::put);
        return MAPPER.writeValueAsString(root);
    }


    @Override
    public ListedTools convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(column);
            if (!root.isObject()) {
                throw new IllegalStateException("connector_connection.listed_tools has an unexpected shape");
            }
            Map<String, Boolean> tools = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                if (!entry.getValue().isBoolean()) {
                    throw new IllegalStateException("connector_connection.listed_tools has an unexpected shape");
                }
                tools.put(entry.getKey(), entry.getValue().booleanValue());
            }
            return new ListedTools(tools);
        } catch (JacksonException ex) {
            throw new IllegalStateException("connector_connection.listed_tools is not valid JSON");
        }
    }
}
