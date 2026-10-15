package com.bifos.assistant.connector.domain;

import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import java.math.BigDecimal;

/**
 * 계좌 주인이 운영 최대치 안에서 정한 주문 상한이다(ADR-20261015 / financial-user-limits).
 *
 * <p>값은 그 사용자의 {@code app_user} 컬럼에 있고, 컬럼의 뜻은 {@code backend/docs/data-schema.md} 의 「app_user」 가 갖는다.
 * 빈 값은 운영 최대치를 따른다. 쓰는 쪽은 같은 트랜잭션에서 그 사용자의 {@code app_user} 행을 먼저 잠근다.
 */
public record FinancialOrderLimit(
    BigDecimal orderAmountKrw,
    BigDecimal orderAmountUsd,
    BigDecimal orderQuantity,
    BigDecimal dailyAmountKrw,
    BigDecimal dailyAmountUsd) {


    /** 아무것도 정하지 않은 상한이다. 다섯 종류 모두 운영 최대치를 따른다. */
    public static final FinancialOrderLimit NONE = new FinancialOrderLimit(null, null, null, null, null);

    /** 사용자가 정한 값이다. 정하지 않았으면 null 이다. */
    public BigDecimal value(FinancialLimitKind kind) {
        return switch (kind) {
            case ORDER_AMOUNT_KRW -> orderAmountKrw;
            case ORDER_AMOUNT_USD -> orderAmountUsd;
            case ORDER_QUANTITY -> orderQuantity;
            case DAILY_AMOUNT_KRW -> dailyAmountKrw;
            case DAILY_AMOUNT_USD -> dailyAmountUsd;
        };
    }


    /** 한 종류만 바꾼 상한이다. null 이면 그 종류는 운영 최대치를 따른다. */
    public FinancialOrderLimit with(FinancialLimitKind kind, BigDecimal value) {
        return new FinancialOrderLimit(
            kind == FinancialLimitKind.ORDER_AMOUNT_KRW ? value : orderAmountKrw,
            kind == FinancialLimitKind.ORDER_AMOUNT_USD ? value : orderAmountUsd,
            kind == FinancialLimitKind.ORDER_QUANTITY ? value : orderQuantity,
            kind == FinancialLimitKind.DAILY_AMOUNT_KRW ? value : dailyAmountKrw,
            kind == FinancialLimitKind.DAILY_AMOUNT_USD ? value : dailyAmountUsd);
    }
}
