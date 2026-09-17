package com.bifos.assistant.usage.domain;

/**
 * 한 달치 실행을 환산한 합계다.
 *
 * <p>구독료와 견주려면 실행 한 줄이 아니라 이 숫자가 필요하다. 가격을 찾지 못한 실행은 합계에서 빠지고
 * {@code unpricedExecutions} 로만 세어진다. 그래야 합계가 실제보다 작다는 것을 화면이 밝힐 수 있다.
 *
 * @param totalMicros 환산 금액의 합. 통화 단위의 100만분의 1
 * @param pricedExecutions 금액이 잡힌 실행 수
 * @param unpricedExecutions 가격을 찾지 못해 금액이 비어 있는 실행 수
 */
public record MonthlyCost(Long totalMicros, Long pricedExecutions, Long unpricedExecutions) {

    public MonthlyCost {
        totalMicros = totalMicros == null ? 0L : totalMicros;
        pricedExecutions = pricedExecutions == null ? 0L : pricedExecutions;
        unpricedExecutions = unpricedExecutions == null ? 0L : unpricedExecutions;
    }
}
