package com.bifos.assistant.connector.connection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkippedSkillTest {

    @Test
    @DisplayName("저장한 목록을 같은 이름과 까닭으로 읽고, 빈 목록은 NULL 로 저장한다")
    void roundTripsAndStoresEmptyAsNull() {
        List<SkippedSkill> skipped = List.of(new SkippedSkill("demo-runner", List.of("terminal_off", "name_taken")));

        assertThat(SkippedSkill.fromJson(SkippedSkill.toJson(skipped))).isEqualTo(skipped);
        assertThat(SkippedSkill.toJson(List.of())).isNull();
        assertThat(SkippedSkill.fromJson(null)).isEmpty();
    }


    @Test
    @DisplayName("깨진 저장값은 연결 목록을 막지 않고 빈 목록으로 읽는다")
    void readsBrokenValueAsEmpty() {
        for (String broken : List.of("{", "{}", "[{\"reasons\":[\"terminal_off\"]}]", "[null]", "[{\"name\":\"a\",\"reasons\":[null]}]")) {
            assertThat(SkippedSkill.fromJson(broken)).as(broken).isEmpty();
        }
    }
}
