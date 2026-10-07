package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 까닭 코드 배열을 JSON 칸에 왕복시킨다. 모르는 코드는 읽기 오류로 올린다. */
@Converter
public class AutonomyReasonsJsonConverter implements AttributeConverter<List<AutonomyReason>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(List<AutonomyReason> value) {
        return JSON.writeValueAsString(value);
    }

    @Override
    public List<AutonomyReason> convertToEntityAttribute(String value) {
        return JSON.readValue(value, new TypeReference<List<AutonomyReason>>() {});
    }
}
