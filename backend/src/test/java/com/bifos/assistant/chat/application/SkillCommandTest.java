package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 메시지 맨 앞의 {@code /이름} 을 스킬 커맨드로 보는 규칙과 Hermes 에 보낼 입력을 본다(ADR-035). */
class SkillCommandTest {

    @Test
    void 이름_뒤에_글이_있으면_이름과_나머지를_나누고_입력이_skill_view_로_읽게_한다() {
        SkillCommand command = SkillCommand.parse("/shopping 이번 주").orElseThrow();

        assertThat(command.name()).isEqualTo("shopping");
        assertThat(command.rest()).isEqualTo("이번 주");
        assertThat(command.hermesInput()).contains("skill_view(name=\"shopping\")", "이번 주");
    }

    @Test
    void 나머지_글의_앞뒤_공백은_뺀다() {
        SkillCommand command = SkillCommand.parse("/shopping   두부 사기  \n").orElseThrow();

        assertThat(command.rest()).isEqualTo("두부 사기");
    }

    @Test
    void 이름만_있으면_나머지가_비고_입력이_절차를_처음부터_진행하게_한다() {
        SkillCommand command = SkillCommand.parse("/shopping").orElseThrow();

        assertThat(command.rest()).isEmpty();
        assertThat(command.hermesInput()).contains("skill_view(name=\"shopping\")", "처음부터");
    }

    @Test
    void 이름_뒤가_공백이나_끝이_아니거나_이름_규칙에_맞지_않으면_커맨드가_아니다() {
        for (String text : new String[] {"/usr/bin 은 뭐야", "/Shopping", "// 주석", "/", "쇼핑 /shopping", ""}) {
            assertThat(SkillCommand.parse(text)).as("「%s」", text).isEmpty();
        }
        assertThat(SkillCommand.parse(null)).isEmpty();
    }

    @Test
    void 이름은_64자까지이고_65자면_커맨드가_아니다() {
        String longest = "a".repeat(64);

        assertThat(SkillCommand.parse("/" + longest + " 해 줘")).map(SkillCommand::name).contains(longest);
        assertThat(SkillCommand.parse("/" + "a".repeat(65) + " 해 줘")).isEmpty();
    }
}
