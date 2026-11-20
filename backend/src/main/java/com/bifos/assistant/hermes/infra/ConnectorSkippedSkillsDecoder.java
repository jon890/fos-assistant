package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.application.port.HermesConnectorClient.SkippedSkill;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 바인딩 설치 응답의 {@code skipped_skills} 를 읽는다(ADR-20261120 / plugin-agent-skills). 떼기와 옛 대시보드 plugin 은 칸이 없어 빈
 * 목록이다. 까닭 낱말은 대시보드가 더할 수 있어 값을 묶지 않는다.
 *
 * <p>안내에만 쓰는 칸이라 모양이 틀려도 설치를 실패로 보지 않는다. 경고를 남기고 빈 목록으로 읽는다. 설치를 실패로 보면 대시보드가 마친
 * 설치의 재시작 대기와 반영 예정을 적지 못한다.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorSkippedSkillsDecoder {

    static List<SkippedSkill> read(JsonNode value, String connectorId) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<SkippedSkill> skipped = new ArrayList<>();
        boolean valid = value.isArray();
        if (valid) {
            for (JsonNode item : value) {
                SkippedSkill one = skippedSkill(item);
                valid = valid && one != null;
                if (one != null) {
                    skipped.add(one);
                }
            }
        }
        if (!valid) {
            log.warn("커넥터 설치 응답의 skipped_skills 모양이 틀려 빈 목록으로 읽는다 connector={}", connectorId);
            return List.of();
        }
        return List.copyOf(skipped);
    }


    /** 빠진 스킬 하나다. 이름이 빈 글이거나 까닭이 빈 글이 아닌 글의 배열이 아니면 null 이다. */
    private static SkippedSkill skippedSkill(JsonNode item) {
        JsonNode name = item.get("name");
        JsonNode reasons = item.get("reasons");
        if (!filled(name) || reasons == null || !reasons.isArray()) {
            return null;
        }
        List<String> words = new ArrayList<>();
        for (JsonNode reason : reasons) {
            if (!filled(reason)) {
                return null;
            }
            words.add(reason.asString());
        }
        return new SkippedSkill(name.asString(), words);
    }


    private static boolean filled(JsonNode value) {
        return value != null && value.isString() && !value.asString().isBlank();
    }
}
