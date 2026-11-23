package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.turn.application.dto.DelegatedSkillCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 「/에이전트 이름 · 스킬」 의 꼴이다(docs/features/assistant-hub/design.md 의 「PR3」 의 글 꼴). */
class DelegatedSkillCommandTest {

    @Test
    @DisplayName("이름에 공백이 있어도 이름, 스킬, 나머지 글을 나눈다")
    void parsesNameSkillAndRest() {
        DelegatedSkillCommand command = DelegatedSkillCommand.parse("/Gmail 담당 · mail-digest 오늘 온 메일\n요약").orElseThrow();

        assertThat(command.agentName()).isEqualTo("Gmail 담당");
        assertThat(command.command().name()).isEqualTo("mail-digest");
        assertThat(command.command().rest()).isEqualTo("오늘 온 메일\n요약");
        assertThat(DelegatedSkillCommand.parse("/커리어 · position-recommender").orElseThrow().command().rest()).isEmpty();
    }


    @Test
    @DisplayName("비서의 /이름, 가운뎃점 앞뒤 공백이 없는 글, 스킬 이름 뒤에 다른 글자가 붙은 글, 줄바꿈이 든 이름은 꼴이 아니다")
    void rejectsOtherForms() {
        assertThat(DelegatedSkillCommand.parse("/shopping 운동화")).isEmpty();
        assertThat(DelegatedSkillCommand.parse("/커리어·position-recommender")).isEmpty();
        assertThat(DelegatedSkillCommand.parse("/커리어 · position_recommender")).isEmpty();
        assertThat(DelegatedSkillCommand.parse("/커리어\n담당 · position-recommender")).isEmpty();
        assertThat(DelegatedSkillCommand.parse("커리어 · position-recommender")).isEmpty();
        assertThat(DelegatedSkillCommand.parse(" · position-recommender")).isEmpty();
        assertThat(DelegatedSkillCommand.parse(null)).isEmpty();
    }


    @Test
    @DisplayName("이름이 소문자 영어여도 비서의 /이름 보다 이 꼴로 읽힌다")
    void winsOverPrimaryCommand() {
        assertThat(DelegatedSkillCommand.parse("/abc · weekly-plan").orElseThrow().agentName()).isEqualTo("abc");
    }
}
