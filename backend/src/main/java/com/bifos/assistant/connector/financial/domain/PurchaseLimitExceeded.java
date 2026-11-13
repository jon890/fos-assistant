package com.bifos.assistant.connector.financial.domain;

import com.bifos.assistant.connector.financial.domain.type.PurchaseLimitKind;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;

/**
 * 주문서의 금액이 구매 상한에 걸렸다(ADR-20261113 / purchase-checkout). 상한은 사용자가 정한 값이라 걸린 상한과 오늘 남은 한도를 밝힌다.
 *
 * <p>상한이 0 이면 사용자가 아직 정하지 않은 것이라 「상한을 정해 주세요」 로 알린다({@link #unset}).
 */
public class PurchaseLimitExceeded extends ApiException {
    private final PurchaseLimitKind kind;
    private final long limit;
    private final long remainingToday;


    public PurchaseLimitExceeded(PurchaseLimitKind kind, long limit, long remainingToday) {
        super(ErrorCode.FINANCIAL_LIMIT_EXCEEDED, "purchase limit exceeded");
        this.kind = kind;
        this.limit = limit;
        this.remainingToday = remainingToday;
    }


    public PurchaseLimitKind kind() {
        return kind;
    }


    public long limit() {
        return limit;
    }


    public long remainingToday() {
        return remainingToday;
    }


    /** 걸린 상한이 0 이다. 사용자가 상한을 정하지 않았다. */
    public boolean unset() {
        return limit == 0;
    }
}
