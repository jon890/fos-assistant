package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 금융 상한을 바꾸지 못한 결과가 바로 알림 끝 줄을 {@code next} 칸으로 갖는지 본다(스펙 tool-fallback REQ2, REQ3). */
class McpFinancialLimitNextTest {

    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "above_maximum", "no_financial_connection", "closed"})
    @DisplayName("바꾸지 못한 상태는 있던 칸을 그대로 두고 바로 알림 next 칸을 더한다")
    void unchangedStatusGetsAskUserNext(String status) {
        Map<String, Object> body = McpFinancialLimitTools.withNext(Map.of("status", status, "name", "order_amount_krw"));

        assertThat(body).containsEntry("status", status).containsEntry("name", "order_amount_krw").containsEntry("next", "사용자에게 바로 알린다.");
    }


    @ParameterizedTest
    @ValueSource(strings = {"applied", "pending"})
    @DisplayName("바꿨거나 승인을 기다리는 결과에는 next 칸이 없다")
    void changedStatusHasNoNext(String status) {
        assertThat(McpFinancialLimitTools.withNext(Map.of("status", status))).doesNotContainKey("next");
    }
}
