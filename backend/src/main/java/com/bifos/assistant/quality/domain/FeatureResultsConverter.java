package com.bifos.assistant.quality.domain;

import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 배포 뒤 확인의 기능별 결과 배열을 {@code quality_run.result_json} 의 글로 바꾸고 읽는다. 칸의 모양이 종류마다 달라 JPA 변환기 대신
 * {@link QualityRun} 이 종류를 확인한 뒤 부른다. 읽지 못한 값은 저장 오류로 전한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FeatureResultsConverter {

    private static final JsonMapper JSON = JsonMapper.builder().build();


    static String write(List<FeatureResult> results) {
        try {
            return JSON.writeValueAsString(results == null ? List.of() : results);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot write quality results", ex);
        }
    }


    static List<FeatureResult> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(Arrays.asList(JSON.readValue(json, FeatureResult[].class)));
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot read quality results", ex);
        }
    }
}
