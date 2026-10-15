package com.bifos.assistant.connector.application.model;

/**
 * 상한 변경 승인 카드에 보일 요약이다(ADR-20261015 / financial-user-limits). 값은 소수점 뒤 0 을 뺀 십진 글이다.
 *
 * @param name 상한의 API 이름
 * @param currency 금액 상한의 통화. 수량 상한은 null 이다
 * @param label 상한의 한국어 이름
 * @param previous 요청할 때 적용하던 상한
 * @param requested 요청한 값
 * @param maximum 지금 운영 최대치
 * @param current 지금 적용하는 상한
 */
public record FinancialLimitChangeSummary(
    String name,
    String currency,
    String label,
    String previous,
    String requested,
    String maximum,
    String current) {
}
