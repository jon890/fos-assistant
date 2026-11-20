package com.bifos.assistant.connector.connection.domain;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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


    /**
     * 저장된 JSON 글을 읽는다. 비었으면 빈 목록이다.
     *
     * <p>안내에만 쓰는 값이라 읽지 못하면 경고를 남기고 빈 목록으로 읽는다. 저장 오류로 전하면 그 에이전트의 연결 목록 전체를 읽지 못한다.
     */
    public static List<SkippedSkill> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            SkippedSkill[] read = JSON.readValue(json, SkippedSkill[].class);
            if (read == null || Arrays.stream(read).anyMatch(skipped -> skipped == null || skipped.name() == null)) {
                throw new IllegalArgumentException("skipped skill without name");
            }
            return List.of(read);
        } catch (JacksonException | IllegalArgumentException ex) {
            log.warn("바인딩의 skipped_skills 를 읽지 못해 빈 목록으로 읽는다: {}", ex.getClass().getSimpleName());
            return List.of();
        }
    }
}
