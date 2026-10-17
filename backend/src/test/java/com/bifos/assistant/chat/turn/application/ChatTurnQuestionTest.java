package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.chat.turn.application.dto.TurnIntent;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatTurnQuestionTest {

    @Test
    @DisplayName("새 질문과 다시 생성만 turn 글을 질문으로 넘기고 자동 turn 은 질문 없이 조립한다")
    void passesQuestionOnlyForUserTurns() {
        assertThat(ChatTurnRunner.questionOf(new TurnIntent.Fresh(), "타이어 언제 갈았지")).isEqualTo("타이어 언제 갈았지");
        assertThat(ChatTurnRunner.questionOf(new TurnIntent.Fresh(List.of(1L, 2L)), "합친 글")).isEqualTo("합친 글");
        assertThat(ChatTurnRunner.questionOf(new TurnIntent.Regenerate(null, null), "다시 묻는 글")).isEqualTo("다시 묻는 글");
        assertThat(ChatTurnRunner.questionOf(new TurnIntent.Scheduled("예약 작업"), "예약 지시")).isNull();
        assertThat(ChatTurnRunner.questionOf(new TurnIntent.DelegationResults(1L, false, List.of()), "결과 전달")).isNull();
        assertThat(ChatTurnRunner.questionOf(new TurnIntent.ProactiveCheck(null), "살펴보기")).isNull();
    }
}
