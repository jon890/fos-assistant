package com.bifos.assistant.chat.turn.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.context.application.dto.InstructionScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** turn 에 넣을 공통 답변 지침의 범위를 의도별로 본다. */
class TurnIntentScopeTest {

    @Test
    @DisplayName("사람이 보낸 turn 과 사람이 있는 트리의 결과를 전하는 turn 은 대화 범위다")
    void conversationTurns() {
        assertThat(TurnIntent.scope(new TurnIntent.Fresh())).isEqualTo(InstructionScope.CONVERSATION);
        assertThat(TurnIntent.scope(new TurnIntent.Regenerate(null, null))).isEqualTo(InstructionScope.CONVERSATION);
        assertThat(TurnIntent.scope(new TurnIntent.Mention())).isEqualTo(InstructionScope.CONVERSATION);
        assertThat(TurnIntent.scope(new TurnIntent.DelegationResults(null, false, List.of())))
            .isEqualTo(InstructionScope.CONVERSATION);
        assertThat(TurnIntent.scope(new TurnIntent.DelegationResults(null, true, List.of())))
            .isEqualTo(InstructionScope.CONVERSATION);
    }


    @Test
    @DisplayName("예약 작업과 사람이 없는 트리의 결과를 전하는 turn 은 예약 범위이고 먼저 살펴보기는 살펴보기 범위다")
    void unattendedTurns() {
        assertThat(TurnIntent.scope(new TurnIntent.Scheduled("알림"))).isEqualTo(InstructionScope.SCHEDULED);
        assertThat(TurnIntent.scope(new TurnIntent.DelegationResults(null, false, List.of(), true)))
            .isEqualTo(InstructionScope.SCHEDULED);
        assertThat(TurnIntent.scope(new TurnIntent.ProactiveCheck(null))).isEqualTo(InstructionScope.PROACTIVE);
    }
}
