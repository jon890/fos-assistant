package com.bifos.assistant.quality.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 기능별 결과 배열을 JSON 칸에 저장하고 읽는다. 읽지 못한 값은 저장 오류로 전한다. */
@Converter
public class FeatureResultsConverter implements AttributeConverter<List<FeatureResult>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public String convertToDatabaseColumn(List<FeatureResult> results) {
        if (results == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(results);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot write quality results", ex);
        }
    }


    @Override
    public List<FeatureResult> convertToEntityAttribute(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return Arrays.asList(JSON.readValue(json, FeatureResult[].class));
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot read quality results", ex);
        }
    }
}
