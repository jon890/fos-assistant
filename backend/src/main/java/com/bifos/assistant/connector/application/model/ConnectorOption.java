package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** 선택지 칸에서 고를 수 있는 항목 하나다. */
public record ConnectorOption(String value, String label) {

    /**
     * 선택지 도구의 결과에서 선언이 가리키는 배열을 항목 목록으로 바꾼다.
     *
     * @return 배열이 없거나 항목의 값과 이름이 문자열이 아니면 비어 있다
     */
    public static Optional<List<ConnectorOption>> listFrom(JsonNode result, ConnectorFieldOptions options) {
        JsonNode items = result.get(options.items());
        if (items == null || !items.isArray()) {
            return Optional.empty();
        }
        List<ConnectorOption> found = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode value = item.get(options.value());
            JsonNode label = item.get(options.label());
            if (value == null || !value.isString() || label == null || !label.isString()) {
                return Optional.empty();
            }
            found.add(new ConnectorOption(value.asString(), label.asString()));
        }
        return Optional.of(List.copyOf(found));
    }
}
