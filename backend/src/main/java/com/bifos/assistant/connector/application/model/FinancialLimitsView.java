package com.bifos.assistant.connector.application.model;

import java.util.List;
import java.util.Map;

/**
 * 한 사용자의 주문 상한과 오늘 쓴 금액이다(ADR-20261015 / financial-user-limits). 화면과 MCP 도구가 함께 쓴다.
 *
 * @param available 허용 목록에 이 사용자의 계좌가 있는가. 거짓이면 상한을 바꿀 수 없다
 * @param limits 다섯 종류의 상한
 * @param spentToday 통화(KRW, USD)마다 KST 오늘 승인한 주문 금액의 합
 * @param remainingToday 통화마다 하루 합계 상한에서 오늘 쓴 금액을 뺀 값. 0 보다 작지 않다
 */
public record FinancialLimitsView(
    boolean available,
    List<Item> limits,
    Map<String, String> spentToday,
    Map<String, String> remainingToday) {

    public FinancialLimitsView {
        limits = List.copyOf(limits);
        spentToday = Map.copyOf(spentToday);
        remainingToday = Map.copyOf(remainingToday);
    }


    /**
     * 상한 하나다. 값은 소수점 뒤 0 을 뺀 십진 글이다.
     *
     * @param name API 이름. {@code order_amount_usd} 처럼 쓴다
     * @param currency 금액 상한의 통화. 수량 상한은 null 이다
     * @param value 지금 적용하는 값
     * @param maximum 운영 최대치
     * @param custom 사용자가 값을 정했는가. 거짓이면 최대치를 따른다
     */
    public record Item(String name, String currency, String label, String value, String maximum, boolean custom) {
    }
}
