package com.bifos.assistant.proactive.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 보고를 JSON 칸에 저장하고 읽는다. 읽지 못한 값은 호출자에게 저장 오류로 전한다. */
@Converter
public class CheckReportJsonConverter implements AttributeConverter<CheckReport, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(CheckReport report) {
        if (report == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(report);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot write check report", ex);
        }
    }

    @Override
    public CheckReport convertToEntityAttribute(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, CheckReport.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot read check report", ex);
        }
    }
}
