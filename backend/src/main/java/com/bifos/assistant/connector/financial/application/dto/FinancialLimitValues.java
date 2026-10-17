package com.bifos.assistant.connector.financial.application.dto;

import com.bifos.assistant.connector.action.domain.type.FinancialLimitKind;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 한 사용자에게 지금 적용하는 주문 상한이다(ADR-20261015 / financial-user-limits).
 *
 * @param values 종류마다 적용하는 값. 사용자 값과 운영 최대치 가운데 작은 것이다
 * @param maximums 종류마다의 운영 최대치
 * @param custom 사용자가 값을 정한 종류
 */
public record FinancialLimitValues(
    Map<FinancialLimitKind, BigDecimal> values,
    Map<FinancialLimitKind, BigDecimal> maximums,
    Set<FinancialLimitKind> custom) {

    public FinancialLimitValues {
        values = new EnumMap<>(values);
        maximums = new EnumMap<>(maximums);
        custom = custom.isEmpty() ? EnumSet.noneOf(FinancialLimitKind.class) : EnumSet.copyOf(custom);
    }


    public BigDecimal value(FinancialLimitKind kind) {
        return values.get(kind);
    }


    public BigDecimal maximum(FinancialLimitKind kind) {
        return maximums.get(kind);
    }


    public boolean custom(FinancialLimitKind kind) {
        return custom.contains(kind);
    }
}
