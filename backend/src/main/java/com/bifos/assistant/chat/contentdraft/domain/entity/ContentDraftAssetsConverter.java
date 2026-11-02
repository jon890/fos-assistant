package com.bifos.assistant.chat.contentdraft.domain.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** revision 의 사진 참조 목록을 {@code assets_json} 문자열 칸에 왕복시킨다. 저장 오류를 침묵으로 바꾸지 않는다. */
@Converter
public class ContentDraftAssetsConverter implements AttributeConverter<List<ContentDraftAsset>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<List<ContentDraftAsset>> TYPE = new TypeReference<>() {
    };


    @Override
    public String convertToDatabaseColumn(List<ContentDraftAsset> value) {
        return value == null ? null : JSON.writeValueAsString(value);
    }


    @Override
    public List<ContentDraftAsset> convertToEntityAttribute(String value) {
        return value == null ? null : List.copyOf(JSON.readValue(value, TYPE));
    }
}
