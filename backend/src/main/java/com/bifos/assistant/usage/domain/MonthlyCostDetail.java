package com.bifos.assistant.usage.domain;

/**
 * 한 달치 실행의 환산액과 실제 청구액을 함께 합친 것이다.
 *
 * <p>{@code pricedExecutions} 와 {@code unpricedExecutions} 는 {@link MonthlyCost} 와 같은 뜻이다.
 * 환산액이 비어 있는지로 센다. {@code subscriptionExecutions} 는 구독 경로로 돈 실행 수이고,
 * 금액이 아니라 실행의 {@code costMode} 로 센다. 화면이 「이만큼은 구독으로 돌았다」 를 말할 수
 * 있게 따로 둔다.
 *
 * @param estimatedMicros 환산 금액의 합. 통화 단위의 100만분의 1
 * @param actualMicros 실제 청구액의 합. 통화 단위의 100만분의 1
 * @param pricedExecutions 환산 금액이 잡힌 실행 수
 * @param unpricedExecutions 가격을 찾지 못해 환산 금액이 비어 있는 실행 수
 * @param subscriptionExecutions 구독 경로로 돈 실행 수
 */
public record MonthlyCostDetail(
        Long estimatedMicros,
        Long actualMicros,
        Long pricedExecutions,
        Long unpricedExecutions,
        Long subscriptionExecutions) {

    public MonthlyCostDetail {
        estimatedMicros = estimatedMicros == null ? 0L : estimatedMicros;
        actualMicros = actualMicros == null ? 0L : actualMicros;
        pricedExecutions = pricedExecutions == null ? 0L : pricedExecutions;
        unpricedExecutions = unpricedExecutions == null ? 0L : unpricedExecutions;
        subscriptionExecutions = subscriptionExecutions == null ? 0L : subscriptionExecutions;
    }
}
