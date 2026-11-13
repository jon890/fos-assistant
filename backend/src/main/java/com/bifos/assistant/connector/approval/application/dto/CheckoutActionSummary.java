package com.bifos.assistant.connector.approval.application.dto;

import java.util.List;

/**
 * 주문서 준비({@code CHECKOUT}) 승인 카드에 보일 요약이다(ADR-20261113 / purchase-checkout). 저장한 summary 의 칸을 옮기고, 오늘 남은
 * 구매 한도는 저장하지 않고 읽을 때 지금 상한과 합계로 계산한다. 배송지와 결제 수단 칸은 없다(spec DECISION8).
 *
 * @param available 저장한 내용을 열어 검증했는가. 거짓이면 나머지 칸은 null 이고 화면은 승인 단추를 그리지 않는다
 * @param regionalExtraFee 지역 추가 배송비가 있는가. 카드는 값과 상관없이 「지역 추가 배송비 별도」 를 적는다(spec DECISION29)
 * @param remainingTodayKrw 하루 구매 상한에서 오늘 승인한 합계를 뺀 값. 읽지 못하면 null
 */
public record CheckoutActionSummary(
    boolean available,
    String title,
    String store,
    List<Option> options,
    Integer quantity,
    Long itemAmountKrw,
    Long shippingFeeKrw,
    Long expectedTotalKrw,
    Boolean regionalExtraFee,
    String url,
    Long remainingTodayKrw) {

    public CheckoutActionSummary {
        options = List.copyOf(options);
    }


    /** 저장한 내용을 열지 못한 줄이다. */
    public static CheckoutActionSummary unavailable() {
        return new CheckoutActionSummary(false, null, null, List.of(), null, null, null, null, null, null, null);
    }


    /** 고른 옵션 하나다. */
    public record Option(String name, String value) {
    }
}
