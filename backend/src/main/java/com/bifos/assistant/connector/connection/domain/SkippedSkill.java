package com.bifos.assistant.connector.connection.domain;

import java.util.Arrays;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 바인딩 설치가 그 에이전트에 설치하지 못한 에이전트용 스킬 하나다(ADR-20261120 / plugin-agent-skills).
 *
 * <p>목록을 {@code agent_connector_binding.skipped_skills} 에 JSON 글로 둔다. 칸의 뜻은 {@code backend/docs/data-schema.md} 의
 * 「agent_connector_binding」 이 갖는다.
 *
 * @param name 스킬 이름
 * @param reasons 대시보드가 정한 까닭 낱말. 대시보드가 낱말을 더할 수 있어 enum 으로 묶지 않는다
 */
public record SkippedSkill(String name, List<String> reasons) {

    /** 모르는 칸을 받는다. 뒤 버전이 칸을 더해도 앞 버전이 읽는다. */
    private static final JsonMapper JSON = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();


    public SkippedSkill {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }


    /** 저장할 JSON 글이다. 빈 목록은 옛 행과 같게 null 이다. */
    public static String toJson(List<SkippedSkill> skipped) {
        if (skipped == null || skipped.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(skipped);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot write skipped skills", ex);
        }
    }


    /** 저장된 JSON 글을 읽는다. 비었으면 빈 목록이다. 읽지 못하면 저장 오류로 전한다. */
    public static List<SkippedSkill> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(Arrays.asList(JSON.readValue(json, SkippedSkill[].class)));
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot read skipped skills", ex);
        }
    }
}
