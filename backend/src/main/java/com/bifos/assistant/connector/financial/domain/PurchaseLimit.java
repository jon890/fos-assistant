package com.bifos.assistant.connector.financial.domain;

import com.bifos.assistant.connector.financial.domain.type.PurchaseLimitKind;

/**
 * 사용자가 정한 구매 상한이다(ADR-20261113 / purchase-checkout). 원 단위이고 운영 최대치는 없다.
 *
 * <p>값은 그 사용자의 {@code app_user} 컬럼에 있고 뜻은 {@code backend/docs/data-schema.md} 의 「app_user」 가 갖는다.
 * 기본 0 은 주문서 준비를 받지 않는 닫힌 상태다. 쓰는 쪽은 같은 트랜잭션에서 그 사용자의 {@code app_user} 행을 먼저 잠근다.
 *
 * @param orderKrw 한 건 상한. 상품 금액과 기본 배송비를 더한 예상 합계와 견준다
 * @param dailyKrw KST 하루 상한. 그날 승인한 주문서 준비의 예상 합계 합과 견준다
 */
public record PurchaseLimit(long orderKrw, long dailyKrw) {

    /** 그 종류의 값이다. */
    public long value(PurchaseLimitKind kind) {
        return switch (kind) {
            case ORDER -> orderKrw;
            case DAILY -> dailyKrw;
        };
    }


    /** 한 종류만 바꾼 상한이다. */
    public PurchaseLimit with(PurchaseLimitKind kind, long value) {
        return new PurchaseLimit(kind == PurchaseLimitKind.ORDER ? value : orderKrw, kind == PurchaseLimitKind.DAILY ? value : dailyKrw);
    }
}
