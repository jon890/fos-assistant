package com.bifos.assistant.feedback.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

/** 정비 사건의 근거를 JSON 칸에 왕복시킨다. 저장 오류를 침묵으로 바꾸지 않는다. */
@Converter
public class MaintenanceDetailConverter implements AttributeConverter<MaintenanceDetail, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public String convertToDatabaseColumn(MaintenanceDetail value) {
        return value == null ? null : JSON.writeValueAsString(value);
    }


    @Override
    public MaintenanceDetail convertToEntityAttribute(String value) {
        return value == null ? null : JSON.readValue(value, MaintenanceDetail.class);
    }
}
