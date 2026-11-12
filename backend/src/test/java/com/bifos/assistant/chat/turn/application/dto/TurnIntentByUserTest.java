package com.bifos.assistant.chat.turn.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 화면 넘기기 카드를 닫는 기준인 「사람이 보낸 turn」 을 의도별로 본다. */
class TurnIntentByUserTest {

    @Test
    @DisplayName("새 질문과 대기 메시지 합침과 다시 생성은 사람이 보낸 turn 이다")
    void userSentTurns() {
        assertThat(TurnIntent.byUser(new TurnIntent.Fresh())).isTrue();
        assertThat(TurnIntent.byUser(new TurnIntent.Fresh(List.of(1L, 2L)))).isTrue();
        assertThat(TurnIntent.byUser(new TurnIntent.Regenerate(null, null))).isTrue();
    }


    @Test
    @DisplayName("결과 전달과 예약 작업과 먼저 살펴보기는 사람이 보낸 turn 이 아니다")
    void automaticTurns() {
        assertThat(TurnIntent.byUser(new TurnIntent.DelegationResults(null, false, List.of()))).isFalse();
        assertThat(TurnIntent.byUser(new TurnIntent.DelegationResults(null, false, List.of(), true))).isFalse();
        assertThat(TurnIntent.byUser(new TurnIntent.DelegationResults(null, true, List.of()))).isFalse();
        assertThat(TurnIntent.byUser(new TurnIntent.Scheduled("알림"))).isFalse();
        assertThat(TurnIntent.byUser(new TurnIntent.ProactiveCheck(null))).isFalse();
    }
}
