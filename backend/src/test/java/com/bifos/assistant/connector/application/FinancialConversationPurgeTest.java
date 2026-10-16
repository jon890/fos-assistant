package com.bifos.assistant.connector.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationPurger;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 실제 암호화한 금융 snapshot과 loopback 실행 결과로 purge 뒤 하루 상한 합계도 확인한다. */
class FinancialConversationPurgeTest extends FinancialConnectorTestBase {
    @Autowired
    ChatService chat;
    @Autowired
    ConversationPurger purger;
    @Autowired
    ConnectorActionService publicApproval;
    @Autowired
    FinancialOrderLimits limits;


    @Test
    @DisplayName("당일 성공 금융의 암호문은 대화 purge 뒤에도 하루 합계에 남고 다음 날 본문만 지운다")
    void preservesSpentAmountUntilTheNextKstDay() {
        var pending = request();
        assertThat(publicApproval.approve(user, pending.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        var action = action(pending);
        var stored = contents.findById(action.id()).orElseThrow();
        assertThat(stored.contentKeyId()).isNotNull();
        Map<String, BigDecimal> spent = limits.spentToday(user.id(), null, now);
        assertThat(spent.get("USD")).isEqualByComparingTo("24.68");
        jdbc.update("UPDATE agent_execution SET status = 'SUCCEEDED' WHERE id = ?", action.originExecutionId());
        chat.delete(user, action.conversationId());

        assertThat(purger.purgeDue(now)).isPositive();
        var retained = contents.findById(action.id()).orElseThrow();
        assertThat(retained.executionArgsJson()).isEqualTo(stored.executionArgsJson());
        assertThat(retained.summaryJson()).isEqualTo(stored.summaryJson());
        assertThat(retained.scopeJson()).isEqualTo(stored.scopeJson());
        assertThat(retained.contentKeyId()).isEqualTo(stored.contentKeyId());
        assertThat(actions.findById(action.id()).orElseThrow().bodyErasedAt()).isNull();
        assertThat(limits.spentToday(user.id(), null, now)).isEqualTo(spent);

        var nextDay = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().plusDays(1)
            .atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        clock.set(nextDay);
        assertThat(purger.purgeDue(nextDay)).isPositive();
        var erased = contents.findById(action.id()).orElseThrow();
        assertThat(erased.executionArgsJson()).isNull();
        assertThat(erased.summaryJson()).isNull();
        assertThat(erased.scopeJson()).isNull();
        assertThat(erased.contentKeyId()).isNull();
        assertThat(erased.requestKey()).isEqualTo(stored.requestKey());
        assertThat(erased.executionArgsSha256()).isEqualTo(stored.executionArgsSha256());
        assertThat(erased.ticketId()).isEqualTo(stored.ticketId());
        assertThat(erased.consumedAt()).isEqualTo(stored.consumedAt());
        assertThat(actions.findById(action.id()).orElseThrow().status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(limits.spentToday(user.id(), null, nextDay)).isEmpty();
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }
}
