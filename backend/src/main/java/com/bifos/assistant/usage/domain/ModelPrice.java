package com.bifos.assistant.usage.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * models.dev 카탈로그가 적어 둔 모델 하나의 가격이다.
 *
 * <p>모든 단가는 100만 토큰당 미국 달러다. 카탈로그가 적지 않은 단가는 null 로 두고, null 을 0 으로
 * 바꾸지 않는다. 0 은 그 토큰이 공짜였다는 뜻이라 가격을 모른다는 사실과 다르다.
 *
 * @param inputUsdPerMillion 캐시에서 오지 않은 입력 토큰의 단가
 * @param outputUsdPerMillion 출력 토큰의 단가
 * @param cacheReadUsdPerMillion 캐시에서 온 입력 토큰의 단가. 카탈로그가 따로 적지 않으면 null 이다
 * @param tiers 문맥 길이 구간. 단일 가격인 모델은 비어 있다
 */
public record ModelPrice(
        BigDecimal inputUsdPerMillion,
        BigDecimal outputUsdPerMillion,
        BigDecimal cacheReadUsdPerMillion,
        List<ContextTier> tiers) {

    /**
     * 실행의 입력이 {@code thresholdTokens} 를 넘으면 적용하는 가격이다.
     *
     * <p>models.dev 는 이 경계값을 {@code tiers[].tier.size} 로 적는다.
     */
    public record ContextTier(
            long thresholdTokens,
            BigDecimal inputUsdPerMillion,
            BigDecimal outputUsdPerMillion,
            BigDecimal cacheReadUsdPerMillion) {
    }

    public ModelPrice {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }

    /** 입력과 출력 어느 쪽도 값을 매길 수 없으면 이 모델의 산정은 통째로 불가능하다. */
    public boolean isUnusable() {
        return inputUsdPerMillion == null && outputUsdPerMillion == null;
    }

    /**
     * 이 크기의 실행에 적용할 단가를 고른다.
     *
     * <p>경계를 넘은 구간 중 가장 큰 것을 쓴다. 구간이 여럿인 모델에서 아주 긴 문맥이 첫 구간이 아니라
     * 가장 높은 구간으로 산정되게 하기 위해서다.
     */
    public ModelPrice atContextSize(long inputTokens) {
        ContextTier applicable = null;
        for (ContextTier tier : tiers) {
            if (inputTokens > tier.thresholdTokens()
                    && (applicable == null || tier.thresholdTokens() > applicable.thresholdTokens())) {
                applicable = tier;
            }
        }
        if (applicable == null) {
            return this;
        }
        return new ModelPrice(
                firstNonNull(applicable.inputUsdPerMillion(), inputUsdPerMillion),
                firstNonNull(applicable.outputUsdPerMillion(), outputUsdPerMillion),
                firstNonNull(applicable.cacheReadUsdPerMillion(), cacheReadUsdPerMillion),
                List.of());
    }

    /**
     * 토큰 수와 100만 토큰당 단가를 마이크로 달러로 바꾼다.
     *
     * <p>마이크로 달러는 1달러의 100만분의 1이고 단가는 100만 토큰당 달러다. 두 100만이 서로 없어지므로
     * 금액은 {@code 토큰 수 × 단가} 가 된다.
     */
    public static BigDecimal microsFor(long tokens, BigDecimal usdPerMillion) {
        if (tokens <= 0 || usdPerMillion == null) {
            return BigDecimal.ZERO;
        }
        return usdPerMillion.multiply(BigDecimal.valueOf(tokens)).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal firstNonNull(BigDecimal preferred, BigDecimal fallback) {
        return preferred == null ? fallback : preferred;
    }
}
