package com.bifos.assistant.usage.domain;

/**
 * 실행 한 번의 환산액과 실제 청구액을 함께 담는다.
 *
 * <p>환산액은 공개된 API 가격표로 계산한 값이고 {@link EstimatedCost} 와 같은 뜻이다. 실제 청구액은
 * 구독 경로에서는 비어 있다. 그 경로는 토큰을 얼마나 쓰든 실제로 추가 청구되는 금액이 없기 때문이다.
 * 종량 경로에서는 아직 provider 의 청구 자료를 읽지 않으므로, 실제 청구액도 같은 공개 가격표로 계산한
 * 값이다.
 *
 * <p>가격을 찾지 못하면 {@link #unknown()} 이 답이다. 두 금액 모두 0 이 아니라 null 로 둔다. 0 은
 * 공짜라는 뜻으로 읽히기 때문이다.
 *
 * @param estimatedMicros 공개 API 가격으로 환산한 금액. 통화 단위의 100만분의 1. 모르면 null
 * @param actualMicros 실제로 청구되는 금액. 구독 경로거나 가격을 모르면 null
 * @param currency ISO 4217 통화 코드. 모르면 null
 * @param pricingVersion 이 금액을 계산한 가격표. 모르면 null
 */
public record ExecutionCost(Long estimatedMicros, Long actualMicros, String currency, String pricingVersion) {

    private static final ExecutionCost UNKNOWN = new ExecutionCost(null, null, null, null);

    public static ExecutionCost unknown() {
        return UNKNOWN;
    }

    public boolean isKnown() {
        return estimatedMicros != null;
    }
}
