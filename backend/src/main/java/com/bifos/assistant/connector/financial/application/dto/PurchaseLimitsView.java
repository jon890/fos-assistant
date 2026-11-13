package com.bifos.assistant.connector.financial.application.dto;

/**
 * 한 사용자의 구매 상한과 오늘 승인한 합계다(ADR-20261113 / purchase-checkout). 금액은 원 단위 정수다.
 *
 * @param orderKrw 한 건 상한. 0 이면 주문서 준비를 받지 않는다
 * @param dailyKrw KST 하루 상한. 0 이면 주문서 준비를 받지 않는다
 * @param spentTodayKrw KST 오늘 승인한 주문서 준비의 예상 합계 합
 * @param remainingTodayKrw 하루 상한에서 오늘 합계를 뺀 값. 0 보다 작지 않다
 */
public record PurchaseLimitsView(long orderKrw, long dailyKrw, long spentTodayKrw, long remainingTodayKrw) {
}
