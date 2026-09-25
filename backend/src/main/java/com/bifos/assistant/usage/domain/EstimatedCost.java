package com.bifos.assistant.usage.domain;

/**
 * 실행 한 번을 공개된 API 가격으로 환산한 금액이다.
 *
 * <p>청구액이 아니다. 구독형 바인딩은 토큰을 얼마나 쓰든 구독료만 청구된다. 이 금액은 같은 토큰을 호출
 * 단위로 샀다면 얼마였을지만 말한다. 사용자가 그 둘을 견주라고 두는 값이다.
 *
 * <p>가격을 찾지 못하면 {@link #unknown()} 이 답이다. 금액은 0 이 아니라 null 로 둔다. 0 은 공짜라는
 * 뜻으로 읽히기 때문이다.
 *
 * @param micros 통화 단위의 100만분의 1로 적은 금액. 모르면 null
 * @param currency ISO 4217 통화 코드. 모르면 null
 * @param pricingVersion 이 금액을 계산한 가격표. 모르면 null
 */
public record EstimatedCost(Long micros, String currency, String pricingVersion) {

    private static final EstimatedCost UNKNOWN = new EstimatedCost(null, null, null);

    public static EstimatedCost unknown() {
        return UNKNOWN;
    }

    public boolean isKnown() {
        return micros != null;
    }
}
