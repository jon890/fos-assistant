package com.bifos.assistant.connector.application.model;

/**
 * 주문 승인 카드에 보일 그 통화의 상한과 오늘 쓴 금액이다(ADR-20261015 / financial-user-limits).
 *
 * <p>금액과 수량은 소수점 뒤 0 을 뺀 십진 글이다. 판정은 승인할 때 서버가 다시 한다.
 *
 * @param orderLimit 그 통화의 한 건 금액 상한
 * @param quantityLimit 한 건 수량 상한
 * @param dailyLimit 그 통화의 하루 합계 상한
 * @param spentToday KST 오늘 승인한 그 통화 주문의 금액 합
 * @param remainingToday 하루 합계 상한에서 오늘 쓴 금액을 뺀 값. 0 보다 작지 않다
 */
public record FinancialLimitStatus(
    String currency,
    String orderLimit,
    String quantityLimit,
    String dailyLimit,
    String spentToday,
    String remainingToday) {
}
