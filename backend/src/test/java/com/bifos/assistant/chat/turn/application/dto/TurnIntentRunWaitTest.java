package com.bifos.assistant.chat.turn.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.runtime.domain.RunWait;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** turn 의 실행이 끝나기를 어느 한도로 기다리는지 의도별로 본다(docs/features/execution.md 의 「실행 한도」). */
class TurnIntentRunWaitTest {

    @Test
    @DisplayName("예약 작업이 연 turn 만 긴 한도로 기다린다")
    void scheduledTurnWaitsLonger() {
        assertThat(TurnIntent.runWait(new TurnIntent.Scheduled("알림"))).isEqualTo(RunWait.BACKGROUND);
    }


    @Test
    @DisplayName("사람이 보낸 turn, 결과를 전하는 turn, 먼저 살펴보기는 사람이 기다리는 실행의 한도를 쓴다")
    void otherTurnsWaitAsConversation() {
        assertThat(TurnIntent.runWait(new TurnIntent.Fresh())).isEqualTo(RunWait.CONVERSATION);
        assertThat(TurnIntent.runWait(new TurnIntent.Regenerate(null, null))).isEqualTo(RunWait.CONVERSATION);
        assertThat(TurnIntent.runWait(new TurnIntent.DelegationResults(null, false, List.of())))
            .isEqualTo(RunWait.CONVERSATION);
        assertThat(TurnIntent.runWait(new TurnIntent.DelegationResults(null, false, List.of(), true)))
            .isEqualTo(RunWait.CONVERSATION);
        assertThat(TurnIntent.runWait(new TurnIntent.ProactiveCheck(null))).isEqualTo(RunWait.CONVERSATION);
    }
}
