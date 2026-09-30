package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.skill.application.SkillFrontmatter;
import com.bifos.assistant.skill.application.SkillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 앞머리의 설명 글자 수와 본문 유무를 Hermes 와 같은 규칙으로 읽는지 본다.
 *
 * <p>기대값은 Hermes v0.21.5 의 식을 손으로 따라 센 것이다. 새 스킬의 설명은
 * {@code len(desc.strip().strip("'\""))}, 모든 저장의 설명은 {@code len(str(description))} 이고 Python 은 code
 * point 로 센다.
 */
class SkillFrontmatterTest {

    @Test
    @DisplayName("한국어 60자 설명은 60으로 센다")
    void countsKorean60CharDescriptionAs60() {
        String sixty = "가".repeat(60);

        SkillFrontmatter frontmatter = SkillFrontmatter.parse(skillMd("description: " + sixty, "# 본문"));

        assertThat(frontmatter.indexedDescriptionLength()).isEqualTo(60);
        assertThat(frontmatter.description()).isEqualTo(sixty);
    }

    @Test
    @DisplayName("색인 글자 수는 앞뒤 공백을 뺀 뒤 양 끝의 따옴표를 몇 개든 뺀다")
    void indexCharCountTrimsThenStripsAnyNumberOfQuotesAtEnds() {
        // YAML 큰따옴표 값이라 원래 값은 「  '가나다'  」 다. 공백을 빼면 「'가나다'」, 따옴표를 빼면 「가나다」.
        SkillFrontmatter spaced = SkillFrontmatter.parse(skillMd("description: \"  '가나다'  \"", "# 본문"));
        // 원래 값은 「"'가나다'"」 다. 양 끝에서 두 종류의 따옴표를 섞어 둘씩 뺀다.
        SkillFrontmatter nested = SkillFrontmatter.parse(skillMd("description: '\"''가나다''\"'", "# 본문"));
        // 원래 값은 「' 가나다 '」 다. Hermes 는 따옴표를 뺀 뒤 공백을 다시 빼지 않는다.
        SkillFrontmatter innerSpace = SkillFrontmatter.parse(skillMd("description: \"' 가나다 '\"", "# 본문"));

        assertThat(spaced.indexedDescriptionLength()).as("앞뒤 공백과 따옴표를 뺀 「가나다」").isEqualTo(3);
        assertThat(nested.indexedDescriptionLength()).as("섞인 따옴표를 뺀 「가나다」").isEqualTo(3);
        assertThat(innerSpace.indexedDescriptionLength())
                .as("따옴표 안쪽 공백은 남는 「 가나다 」")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("이모지 하나는 한 글자로 센다")
    void countsOneEmojiAsOneChar() {
        SkillFrontmatter frontmatter = SkillFrontmatter.parse(skillMd("description: 요리 🍳", "# 본문"));

        assertThat("요리 🍳".length()).as("UTF-16 단위로는 이모지가 둘이다").isEqualTo(5);
        assertThat(frontmatter.indexedDescriptionLength()).isEqualTo(4);
        assertThat(frontmatter.rawDescriptionLength()).isEqualTo(4);
    }

    @Test
    @DisplayName("원문 설명 글자 수는 앞뒤를 빼지 않는다")
    void originalDescriptionCharCountDoesNotTrim() {
        SkillFrontmatter frontmatter = SkillFrontmatter.parse(skillMd("description: \"  '가나다'  \"", "# 본문"));

        assertThat(frontmatter.rawDescription()).isEqualTo("  '가나다'  ");
        assertThat(frontmatter.rawDescriptionLength()).isEqualTo(9);
        assertThat(frontmatter.description()).as("화면에 보이는 설명은 앞뒤 공백만 뺀다").isEqualTo("'가나다'");
    }

    @Test
    @DisplayName("날짜처럼 보이는 설명은 적힌 글자 그대로 세어 60자 한도에 걸리지 않는다")
    void countsDateLikeDescriptionAsWrittenSoLimit60IsNotHit() {
        // Hermes 는 이 값을 날짜로 읽고 str() 로 「2024-01-01」 을 만든다. 시간대에 따라 바뀌는 글이 되면 안 된다.
        SkillFrontmatter date = SkillFrontmatter.parse(skillMd("description: 2024-01-01", "# 본문"));
        SkillFrontmatter dateTime = SkillFrontmatter.parse(skillMd("description: 2024-01-01 10:00:00", "# 본문"));

        assertThat(date.description()).isEqualTo("2024-01-01");
        assertThat(date.rawDescription()).isEqualTo("2024-01-01");
        assertThat(date.indexedDescriptionLength())
                .isEqualTo(10)
                .isLessThanOrEqualTo(SkillService.MAX_NEW_DESCRIPTION_CHARS);
        assertThat(dateTime.rawDescription()).isEqualTo("2024-01-01 10:00:00");
        assertThat(dateTime.rawDescriptionLength()).isEqualTo(19);
    }

    @Test
    @DisplayName("앞머리 뒤에 글이 있으면 본문이 있다")
    void hasBodyWhenTextFollowsFrontmatter() {
        assertThat(SkillFrontmatter.parse(skillMd("description: 계획", "# 본문")).hasBody())
                .isTrue();
        assertThat(SkillFrontmatter.parse(skillMd("description: 계획", "\n\n  본문은 빈 줄 뒤에 있다"))
                        .hasBody())
                .as("빈 줄 뒤의 글도 본문이다")
                .isTrue();
    }

    @Test
    @DisplayName("앞머리 뒤가 비었거나 공백과 빈 줄뿐이면 본문이 없고 읽기는 성공한다")
    void hasNoBodyWhenNothingOrBlankLinesFollowFrontmatterAndReadSucceeds() {
        SkillFrontmatter closedAtEnd = SkillFrontmatter.parse("---\nname: weekly-plan\ndescription: 계획\n---");
        SkillFrontmatter emptyAfter = SkillFrontmatter.parse(skillMd("description: 계획", ""));
        SkillFrontmatter blankAfter = SkillFrontmatter.parse(skillMd("description: 계획", "  \n\t\n\r\n   "));

        assertThat(closedAtEnd.hasBody()).as("닫는 줄로 끝난다").isFalse();
        assertThat(emptyAfter.hasBody()).as("닫는 줄 뒤에 줄바꿈만 있다").isFalse();
        assertThat(blankAfter.hasBody()).as("닫는 줄 뒤에 공백과 빈 줄만 있다").isFalse();
        assertThat(blankAfter.name()).isEqualTo("weekly-plan");
        assertThat(blankAfter.description()).isEqualTo("계획");
    }

    private static String skillMd(String descriptionLine, String body) {
        return "---\nname: weekly-plan\n" + descriptionLine + "\n---\n" + body;
    }
}
