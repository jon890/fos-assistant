package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Hermes 스킬 이름 규칙과, {@code skill_view} 미리보기에서 이름을 꺼내는 것을 검사한다. */
class HermesSkillNameTest {

    @Test
    @DisplayName("소문자와 숫자와 점과 밑줄과 붙임표로 된 64자 이내의 이름은 맞다")
    void acceptsLowercaseDigitsDotUnderscoreHyphenUpTo64Chars() {
        assertThat(HermesSkillName.isValid("shopping")).isTrue();
        assertThat(HermesSkillName.isValid("note_taking.v2")).isTrue();
        assertThat(HermesSkillName.isValid("7-day-plan")).isTrue();
        assertThat(HermesSkillName.isValid("a")).isTrue();
        assertThat(HermesSkillName.isValid("a".repeat(64))).isTrue();
    }

    @Test
    @DisplayName("대문자와 공백과 65자와 빈 값과 점으로 시작하는 이름은 맞지 않는다")
    void rejectsUppercaseSpaceTooLongEmptyAndLeadingDot() {
        assertThat(HermesSkillName.isValid(null)).isFalse();
        assertThat(HermesSkillName.isValid("")).isFalse();
        assertThat(HermesSkillName.isValid("Shopping")).isFalse();
        assertThat(HermesSkillName.isValid("weekly shopping")).isFalse();
        assertThat(HermesSkillName.isValid("a".repeat(65))).isFalse();
        assertThat(HermesSkillName.isValid("..")).isFalse();
        assertThat(HermesSkillName.isValid("../secret")).isFalse();
        assertThat(HermesSkillName.isValid("-dash")).isFalse();
        assertThat(HermesSkillName.isValid("_x")).isFalse();
    }

    @Test
    @DisplayName("미리보기가 이름뿐이면 그 이름을, 참고 파일 경로가 붙으면 화살표 앞의 이름을 꺼낸다")
    void extractsNameFromNameOnlyAndNameWithPathPreview() {
        assertThat(HermesSkillName.fromPreview("shopping")).isEqualTo("shopping");
        assertThat(HermesSkillName.fromPreview("shopping → references/list.md")).isEqualTo("shopping");
        assertThat(HermesSkillName.fromPreview("  shopping  ")).isEqualTo("shopping");
    }

    @Test
    @DisplayName("미리보기의 이름이 규칙에 맞지 않으면 꺼내지 않는다")
    void extractsNothingWhenPreviewNameBreaksRule() {
        assertThat(HermesSkillName.fromPreview(null)).isNull();
        assertThat(HermesSkillName.fromPreview("")).isNull();
        assertThat(HermesSkillName.fromPreview("   ")).isNull();
        assertThat(HermesSkillName.fromPreview("Shopping → references/list.md")).isNull();
        assertThat(HermesSkillName.fromPreview("weekly shopping")).isNull();
        assertThat(HermesSkillName.fromPreview("a".repeat(65))).isNull();
        assertThat(HermesSkillName.fromPreview("../secret → a.md")).isNull();
        assertThat(HermesSkillName.fromPreview("→ references/list.md")).isNull();
    }
}
