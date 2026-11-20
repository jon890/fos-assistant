package com.bifos.assistant.memory.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code memory_capture.replaced_memory_ids} 의 JSON 배열 텍스트와 번호 목록을 왕복시킨다.
 *
 * <p>빈 목록은 {@code NULL} 로 쓰고 {@code NULL} 은 빈 목록으로 읽는다. 합치기가 아닌 기록이 칸을 채우지 않게 한다. 모양이 어긋나면 침묵하지 않고
 * 실패한다.
 */
@Converter
public class ReplacedMemoryIdsConverter implements AttributeConverter<List<Long>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public String convertToDatabaseColumn(List<Long> ids) {
        return ids == null || ids.isEmpty() ? null : JSON.writeValueAsString(ids);
    }


    @Override
    public List<Long> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        JsonNode root = JSON.readTree(column);
        if (!root.isArray()) {
            throw new IllegalStateException("memory_capture.replaced_memory_ids has an unexpected shape");
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.isIntegralNumber()) {
                throw new IllegalStateException("memory_capture.replaced_memory_ids has an unexpected shape");
            }
            ids.add(node.longValue());
        }
        return List.copyOf(ids);
    }
}
