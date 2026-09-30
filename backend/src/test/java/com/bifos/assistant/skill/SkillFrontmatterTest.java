package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.skill.application.SkillFrontmatter;
import com.bifos.assistant.skill.application.SkillService;
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
    void 한국어_60자_설명은_60으로_센다() {
        String sixty = "가".repeat(60);

        SkillFrontmatter frontmatter = SkillFrontmatter.parse(skillMd("description: " + sixty, "# 본문"));

        assertThat(frontmatter.indexedDescriptionLength()).isEqualTo(60);
        assertThat(frontmatter.description()).isEqualTo(sixty);
    }

    @Test
    void 색인_글자_수는_앞뒤_공백을_뺀_뒤_양_끝의_따옴표를_몇_개든_뺀다() {
        // YAML 큰따옴표 값이라 원래 값은 「  '가나다'  」 다. 공백을 빼면 「'가나다'」, 따옴표를 빼면 「가나다」.
        SkillFrontmatter spaced = SkillFrontmatter.parse(skillMd("description: \"  '가나다'  \"", "# 본문"));
        // 원래 값은 「"'가나다'"」 다. 양 끝에서 두 종류의 따옴표를 섞어 둘씩 뺀다.
        SkillFrontmatter nested = SkillFrontmatter.parse(skillMd("description: '\"''가나다''\"'", "# 본문"));
        // 원래 값은 「' 가나다 '」 다. Hermes 는 따옴표를 뺀 뒤 공백을 다시 빼지 않는다.
        SkillFrontmatter innerSpace = SkillFrontmatter.parse(skillMd("description: \"' 가나다 '\"", "# 본문"));

        assertThat(spaced.indexedDescriptionLength()).as("앞뒤 공백과 따옴표를 뺀 「가나다」").isEqualTo(3);
        assertThat(nested.indexedDescriptionLength()).as("섞인 따옴표를 뺀 「가나다」").isEqualTo(3);
        assertThat(innerSpace.indexedDescriptionLength()).as("따옴표 안쪽 공백은 남는 「 가나다 」").isEqualTo(5);
    }

    @Test
    void 이모지_하나는_한_글자로_센다() {
        SkillFrontmatter frontmatter = SkillFrontmatter.parse(skillMd("description: 요리 🍳", "# 본문"));

        assertThat("요리 🍳".length()).as("UTF-16 단위로는 이모지가 둘이다").isEqualTo(5);
        assertThat(frontmatter.indexedDescriptionLength()).isEqualTo(4);
        assertThat(frontmatter.rawDescriptionLength()).isEqualTo(4);
    }

    @Test
    void 원문_설명_글자_수는_앞뒤를_빼지_않는다() {
        SkillFrontmatter frontmatter = SkillFrontmatter.parse(skillMd("description: \"  '가나다'  \"", "# 본문"));

        assertThat(frontmatter.rawDescription()).isEqualTo("  '가나다'  ");
        assertThat(frontmatter.rawDescriptionLength()).isEqualTo(9);
        assertThat(frontmatter.description()).as("화면에 보이는 설명은 앞뒤 공백만 뺀다").isEqualTo("'가나다'");
    }

    @Test
    void 날짜처럼_보이는_설명은_적힌_글자_그대로_세어_60자_한도에_걸리지_않는다() {
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
    void 앞머리_뒤에_글이_있으면_본문이_있다() {
        assertThat(SkillFrontmatter.parse(skillMd("description: 계획", "# 본문")).hasBody()).isTrue();
        assertThat(SkillFrontmatter.parse(skillMd("description: 계획", "\n\n  본문은 빈 줄 뒤에 있다")).hasBody())
                .as("빈 줄 뒤의 글도 본문이다")
                .isTrue();
    }

    @Test
    void 앞머리_뒤가_비었거나_공백과_빈_줄뿐이면_본문이_없고_읽기는_성공한다() {
        SkillFrontmatter closedAtEnd =
                SkillFrontmatter.parse("---\nname: weekly-plan\ndescription: 계획\n---");
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
