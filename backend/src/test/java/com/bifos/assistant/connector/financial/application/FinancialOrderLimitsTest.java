package com.bifos.assistant.connector.financial.application;

import com.bifos.assistant.connector.financial.config.ConnectorFinancialProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.connector.financial.domain.FinancialOrderLimit;
import com.bifos.assistant.connector.action.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.financial.infra.FinancialOrderLimitRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.config.LiveProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FinancialOrderLimitsTest {
    private final FinancialOrderLimitRepository rows = mock(FinancialOrderLimitRepository.class);


    @Test
    @DisplayName("사용자 값이 없으면 다섯 상한 모두 운영 최대치다")
    void usesMaximumsWithoutUserRow() {
        var values = limits().effective(9L);
        assertThat(values.value(FinancialLimitKind.ORDER_AMOUNT_KRW)).isEqualByComparingTo("100000");
        assertThat(values.value(FinancialLimitKind.ORDER_AMOUNT_USD)).isEqualByComparingTo("1000");
        assertThat(values.value(FinancialLimitKind.ORDER_QUANTITY)).isEqualByComparingTo("10");
        assertThat(values.value(FinancialLimitKind.DAILY_AMOUNT_KRW)).isEqualByComparingTo("300000");
        assertThat(values.value(FinancialLimitKind.DAILY_AMOUNT_USD)).isEqualByComparingTo("1000");
        assertThat(values.custom(FinancialLimitKind.ORDER_AMOUNT_USD)).isFalse();
    }


    @Test
    @DisplayName("사용자 값이 최대치보다 작으면 사용자 값, 크면 최대치이고 0 은 0 이다")
    void takesSmallerOfUserValueAndMaximum() {
        var row = FinancialOrderLimit.NONE.with(FinancialLimitKind.ORDER_AMOUNT_USD, new BigDecimal("500"))
            .with(FinancialLimitKind.DAILY_AMOUNT_USD, new BigDecimal("5000"))
            .with(FinancialLimitKind.ORDER_QUANTITY, BigDecimal.ZERO);
        when(rows.findByUserId(9L)).thenReturn(Optional.of(row));
        var values = limits().effective(9L);
        assertThat(values.value(FinancialLimitKind.ORDER_AMOUNT_USD)).isEqualByComparingTo("500");
        assertThat(values.value(FinancialLimitKind.DAILY_AMOUNT_USD)).isEqualByComparingTo("1000");
        assertThat(values.value(FinancialLimitKind.ORDER_QUANTITY)).isEqualByComparingTo("0");
        assertThat(values.value(FinancialLimitKind.ORDER_AMOUNT_KRW)).isEqualByComparingTo("100000");
        assertThat(values.custom(FinancialLimitKind.ORDER_AMOUNT_USD)).isTrue();
        assertThat(values.maximum(FinancialLimitKind.ORDER_AMOUNT_USD)).isEqualByComparingTo("1000");
    }


    private FinancialOrderLimits limits() {
        var live = LiveProperties.fixed(ConnectorFinancialProperties.class, new ConnectorFinancialProperties(
            List.of("7:owner@example.test"), new BigDecimal("100000"), new BigDecimal("1000"), new BigDecimal("10"),
            new BigDecimal("300000"), new BigDecimal("1000"), ""));
        return new FinancialOrderLimits(live, rows, mock(ConnectorActionRepository.class), mock(ConnectorActionExecutionRepository.class),
            mock(TextCipher.class), new FinancialDigest(live));
    }
}
