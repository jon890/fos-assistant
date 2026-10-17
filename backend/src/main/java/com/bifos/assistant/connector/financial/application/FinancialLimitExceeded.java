package com.bifos.assistant.connector.financial.application;

import com.bifos.assistant.connector.action.domain.type.FinancialLimitKind;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.math.BigDecimal;

/**
 * 주문이 적용 상한에 걸렸다(ADR-20261015 / financial-user-limits). 다른 금융 거절과 달리 걸린 상한을 밝힌다.
 *
 * <p>상한은 사용자가 정한 값이라 감추지 않는다. 허용 계좌, key, scope 의 거절은 이 예외를 쓰지 않는다.
 */
public class FinancialLimitExceeded extends ApiException {
    private final FinancialLimitKind kind;
    private final BigDecimal limit;
    private final BigDecimal remainingToday;


    /** @param remainingToday 그 통화의 오늘 남은 한도. 수량 상한이면 null 이다 */
    public FinancialLimitExceeded(FinancialLimitKind kind, BigDecimal limit, BigDecimal remainingToday) {
        super(ErrorCode.FINANCIAL_LIMIT_EXCEEDED, "financial order limit exceeded");
        this.kind = kind;
        this.limit = limit;
        this.remainingToday = remainingToday;
    }


    public FinancialLimitKind kind() {
        return kind;
    }


    public BigDecimal limit() {
        return limit;
    }


    public BigDecimal remainingToday() {
        return remainingToday;
    }
}
