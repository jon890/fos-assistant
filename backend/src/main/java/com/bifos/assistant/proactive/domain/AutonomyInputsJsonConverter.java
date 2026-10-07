package com.bifos.assistant.proactive.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

/** 판정 입력을 JSON 칸에 왕복시킨다. 저장 오류를 침묵으로 바꾸지 않는다. */
@Converter
public class AutonomyInputsJsonConverter implements AttributeConverter<AutonomyInputs, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(AutonomyInputs value) {
        return JSON.writeValueAsString(value);
    }

    @Override
    public AutonomyInputs convertToEntityAttribute(String value) {
        return JSON.readValue(value, AutonomyInputs.class);
    }
}
