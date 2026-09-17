package com.bifos.assistant.usage.application;

import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.usage.domain.EstimatedCost;
import com.bifos.assistant.usage.domain.ModelPrice;
import com.bifos.assistant.usage.domain.PriceCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 실행 한 번의 토큰 수를 공개된 API 가격으로 환산한다.
 *
 * <p>조회할 때가 아니라 실행이 끝나는 시점에 계산하고, 쓴 가격표를 금액과 함께 저장한다. 그래야 나중에
 * 가격이 바뀌어도 지난달 합계가 달라지지 않는다.
 */
@Service
public class CostEstimator {

    private static final String CURRENCY = "USD";

    private final PriceCatalog catalog;

    public CostEstimator(PriceCatalog catalog) {
        this.catalog = catalog;
    }

    public EstimatedCost estimate(String provider, String model, TokenUsage usage) {
        if (usage == null) {
            return EstimatedCost.unknown();
        }
        Optional<ModelPrice> found = catalog.find(provider, model);
        if (found.isEmpty() || found.get().isUnusable()) {
            return EstimatedCost.unknown();
        }

        long inputTokens = nonNegative(usage.inputTokens());
        long cachedTokens = nonNegative(usage.cachedInputTokens());
        long outputTokens = nonNegative(usage.outputTokens());
        if (inputTokens == 0 && outputTokens == 0) {
            return EstimatedCost.unknown();
        }

        ModelPrice price = found.get().atContextSize(inputTokens);

        // 우리가 읽는 provider 는 캐시된 토큰 수를 입력 토큰 수 안에 넣어 보고한다. 그래서 입력 단가로
        // 세는 것은 입력에서 캐시를 뺀 나머지다. 캐시 토큰을 보고하지 않는 provider 는 입력 전체가
        // 그대로 남는데, 그것이 의도한 결과다.
        long uncachedTokens = Math.max(0, inputTokens - cachedTokens);
        long cachedCharged = Math.min(inputTokens, cachedTokens);

        // 카탈로그에 캐시 단가가 없는 모델은 캐시 읽기를 할인한다는 근거가 없다. 그래서 그 토큰도 입력
        // 단가로 센다. 공짜로 떨어뜨리지 않는다.
        BigDecimal cacheRate =
                price.cacheReadUsdPerMillion() == null
                        ? price.inputUsdPerMillion()
                        : price.cacheReadUsdPerMillion();

        BigDecimal micros =
                ModelPrice.microsFor(uncachedTokens, price.inputUsdPerMillion())
                        .add(ModelPrice.microsFor(cachedCharged, cacheRate))
                        .add(ModelPrice.microsFor(outputTokens, price.outputUsdPerMillion()));

        return new EstimatedCost(
                micros.setScale(0, RoundingMode.HALF_UP).longValueExact(), CURRENCY, catalog.version());
    }

    private static long nonNegative(Long value) {
        return value == null || value < 0 ? 0 : value;
    }
}
