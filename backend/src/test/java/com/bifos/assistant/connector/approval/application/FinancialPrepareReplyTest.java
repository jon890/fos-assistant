package com.bifos.assistant.connector.approval.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.approval.application.dto.FinancialPrepareStage;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 금융 준비 거절의 단계마다 모델이 받는 답을 본다. 같은 의도 차단의 세 단계만 까닭을 밝힌다. */
class FinancialPrepareReplyTest {
    private static final Set<FinancialPrepareStage> DISCLOSED = EnumSet.of(FinancialPrepareStage.CONFLICT,
        FinancialPrepareStage.CONFLICT_PENDING, FinancialPrepareStage.CONFLICT_PENDING_ELSEWHERE);


    @Test
    @DisplayName("같은 의도 차단의 세 단계만 상태를 밝히고, 조회 실패와 카드 없는 승인 대기를 포함한 나머지는 일반 답을 받는다")
    void onlyIntentConflictsAreDisclosed() {
        for (FinancialPrepareStage stage : FinancialPrepareStage.values()) {
            String message = ConnectorFinancialIntake.rejectedMessage(new FinancialPrepareRejected(stage));
            if (DISCLOSED.contains(stage)) {
                assertThat(message).as(stage.code()).isNotEqualTo(ConnectorFinancialIntake.FINANCIAL_UNAVAILABLE_MESSAGE);
            } else {
                assertThat(message).as(stage.code()).isEqualTo(ConnectorFinancialIntake.FINANCIAL_UNAVAILABLE_MESSAGE);
            }
        }
        assertThat(ConnectorFinancialIntake.rejectedMessage(new FinancialPrepareRejected(FinancialPrepareStage.CONFLICT_CHECK)))
            .doesNotContain("실행 중");
        assertThat(ConnectorFinancialIntake.rejectedMessage(new IllegalStateException("x")))
            .isEqualTo(ConnectorFinancialIntake.FINANCIAL_UNAVAILABLE_MESSAGE);
    }
}
