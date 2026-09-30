package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 메시지 맨 앞의 {@code /이름} 을 스킬 커맨드로 보는 규칙과 Hermes 에 보낼 입력을 본다(ADR-035). */
class SkillCommandTest {

    @Test
    @DisplayName("이름 뒤에 글이 있으면 이름과 나머지를 나누고 입력이 skill view 로 읽게 한다")
    void splitsNameAndRestAndMakesInputReadViaSkillView() {
        SkillCommand command = SkillCommand.parse("/shopping 이번 주").orElseThrow();

        assertThat(command.name()).isEqualTo("shopping");
        assertThat(command.rest()).isEqualTo("이번 주");
        assertThat(command.hermesInput()).contains("skill_view(name=\"shopping\")", "이번 주");
    }

    @Test
    @DisplayName("나머지 글의 앞뒤 공백은 뺀다")
    void trimsWhitespaceAroundRest() {
        SkillCommand command = SkillCommand.parse("/shopping   두부 사기  \n").orElseThrow();

        assertThat(command.rest()).isEqualTo("두부 사기");
    }

    @Test
    @DisplayName("이름만 있으면 나머지가 비고 입력이 절차를 처음부터 진행하게 한다")
    void nameOnlyLeavesRestEmptyAndInputRunsProcedureFromStart() {
        SkillCommand command = SkillCommand.parse("/shopping").orElseThrow();

        assertThat(command.rest()).isEmpty();
        assertThat(command.hermesInput()).contains("skill_view(name=\"shopping\")", "처음부터");
    }

    @Test
    @DisplayName("이름 뒤가 공백이나 끝이 아니거나 이름 규칙에 맞지 않으면 커맨드가 아니다")
    void isNotCommandWhenAfterNameIsNotSpaceOrEndOrNameBreaksRule() {
        for (String text : new String[] {"/usr/bin 은 뭐야", "/Shopping", "// 주석", "/", "쇼핑 /shopping", ""}) {
            assertThat(SkillCommand.parse(text)).as("「%s」", text).isEmpty();
        }
        assertThat(SkillCommand.parse(null)).isEmpty();
    }

    /** 이름 뒤 공백은 Java 정규식의 {@code \s} 인 공백, 탭, 줄바꿈, 세로 탭, 폼 피드, 캐리지 리턴만 받는다. */
    @Test
    @DisplayName("이름 뒤가 전각 공백이나 NBSP 면 커맨드가 아니고 탭이나 줄바꿈이면 커맨드다")
    void fullWidthSpaceOrNbspAfterNameIsNotCommandButTabOrNewlineIs() {
        assertThat(SkillCommand.parse("/shopping　이번 주")).as("전각 공백").isEmpty();
        assertThat(SkillCommand.parse("/shopping 이번 주")).as("NBSP").isEmpty();
        assertThat(SkillCommand.parse("/shopping\t이번 주")).as("탭").map(SkillCommand::rest).contains("이번 주");
        assertThat(SkillCommand.parse("/shopping\n이번 주")).as("줄바꿈").map(SkillCommand::rest).contains("이번 주");
    }

    @Test
    @DisplayName("이름은 64자까지이고 65자면 커맨드가 아니다")
    void acceptsNameUpTo64CharsAndRejects65() {
        String longest = "a".repeat(64);

        assertThat(SkillCommand.parse("/" + longest + " 해 줘")).map(SkillCommand::name).contains(longest);
        assertThat(SkillCommand.parse("/" + "a".repeat(65) + " 해 줘")).isEmpty();
    }
}
