package com.bifos.assistant.proactive.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 문제 후보의 근거 목록을 JSON 배열 칸에 저장하고 읽는다. 읽지 못한 값은 호출자에게 저장 오류로 전한다. */
@Converter
public class ProblemEvidenceJsonConverter implements AttributeConverter<List<ProblemEvidence>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<ProblemEvidence>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<ProblemEvidence> evidence) {
        try {
            return JSON.writeValueAsString(evidence == null ? List.of() : evidence);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot write problem evidence", ex);
        }
    }

    @Override
    public List<ProblemEvidence> convertToEntityAttribute(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.copyOf(JSON.readValue(json, TYPE));
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot read problem evidence", ex);
        }
    }
}
