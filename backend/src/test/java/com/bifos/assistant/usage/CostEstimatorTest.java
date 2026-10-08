package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.usage.application.CostEstimator;
import com.bifos.assistant.usage.domain.CatalogPrice;
import com.bifos.assistant.usage.domain.EstimatedCost;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.domain.ModelPrice;
import com.bifos.assistant.usage.domain.PriceCatalog;
import com.bifos.assistant.usage.infra.ModelsDevPriceCatalog;
import com.bifos.assistant.usage.infra.PricingProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 구독제로 돌고 있어도 화면이 금액을 보이려면 이 환산이 맞아야 한다.
 *
 * <p>표본 카탈로그는 models.dev 가 내려 주는 모양을 줄인 것이다. {@code example-model} 항목은
 * 실제 단가와 구간을 사용하되 모델 이름은 자리표시자로 바꿨다.
 */
class CostEstimatorTest {

    private static final String CODEX_PROVIDER = "openai-codex";

    private static Path catalogFile;
    private static CostEstimator estimator;

    private static LiveProperties<PricingProperties> pricing(String catalogPath) {
        return LiveProperties.fixed(PricingProperties.class, new PricingProperties(catalogPath));
    }

    @BeforeAll
    static void loadCatalog() throws URISyntaxException, IOException {
        catalogFile = Path.of(CostEstimatorTest.class
                .getResource("/pricing/models-dev-sample.json")
                .toURI());
        // pricing_version 은 파일의 수정 시각에서 나온다. 검사에서 그 날짜를 못 박는다.
        Files.setLastModifiedTime(catalogFile, FileTime.from(Instant.parse("2026-09-17T04:00:00Z")));
        estimator = new CostEstimator(new ModelsDevPriceCatalog(pricing(catalogFile.toString())));
    }

    @Test
    @DisplayName("카탈로그에서 가격을 찾아 금액을 계산한다")
    void calculatesAmountByFindingPriceInCatalog() {
        // 입력 1000 × 5 + 출력 500 × 30 = 20000 마이크로 달러
        EstimatedCost cost = estimator.estimate("openai", "example-model", usage(1000L, null, 500L));

        assertThat(cost.micros()).isEqualTo(20_000L);
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.pricingVersion()).isEqualTo("models.dev@2026-09-17");
    }

    @Test
    @DisplayName("캐시된 입력은 캐시 단가로 세고 나머지만 입력 단가로 센다")
    void countsCachedInputAtCacheRateAndOnlyRestAtInputRate() {
        // 입력 1000 중 800 이 캐시다. 200 × 5 + 800 × 0.5 + 500 × 30 = 16400
        EstimatedCost cost = estimator.estimate("openai", "example-model", usage(1000L, 800L, 500L));

        assertThat(cost.micros()).isEqualTo(16_400L);
    }

    @Test
    @DisplayName("캐시 단가가 없는 모델은 캐시 토큰도 입력 단가로 센다")
    void countsCacheTokensAtInputRateForModelWithoutCacheRate() {
        // 할인한다는 근거가 없으므로 공짜로 떨어뜨리지 않는다. 1000 × 2 + 100 × 8 = 2800
        EstimatedCost cost = estimator.estimate("openai", "gpt-flat", usage(1000L, 400L, 100L));

        assertThat(cost.micros()).isEqualTo(2_800L);
    }

    @Test
    @DisplayName("구간 경계를 넘으면 그 구간의 단가를 쓴다")
    void usesTierRateOnceTierBoundaryIsExceeded() {
        // 경계는 272000 이다. 300000 × 10 + 1000 × 45 = 3045000
        EstimatedCost cost = estimator.estimate("openai", "example-model", usage(300_000L, null, 1_000L));

        assertThat(cost.micros()).isEqualTo(3_045_000L);
    }

    @Test
    @DisplayName("구간 경계와 같으면 아직 기본 단가를 쓴다")
    void stillUsesBaseRateWhenEqualToTierBoundary() {
        // 272000 × 5 = 1360000. 경계를 넘을 때만 올라간다
        EstimatedCost cost = estimator.estimate("openai", "example-model", usage(272_000L, null, 0L));

        assertThat(cost.micros()).isEqualTo(1_360_000L);
    }

    @Test
    @DisplayName("경계값을 적지 않은 옛 항목은 20만 토큰을 경계로 본다")
    void oldEntryWithoutBoundaryTreatsTwoHundredThousandTokensAsBoundary() {
        // gpt-legacy-band 는 context_over_200k 만 적어 두었다. 250000 × 6 + 10 × 12 = 1500120
        EstimatedCost cost = estimator.estimate("openai", "gpt-legacy-band", usage(250_000L, null, 10L));

        assertThat(cost.micros()).isEqualTo(1_500_120L);
    }

    @Test
    @DisplayName("provider 별칭이 동작한다")
    void providerAliasWorks() {
        // 바인딩은 openai-codex 를 적지만 카탈로그는 그 모델을 openai 아래에 둔다
        EstimatedCost aliased = estimator.estimate(CODEX_PROVIDER, "example-model", usage(1000L, null, 500L));

        assertThat(aliased.micros()).isEqualTo(20_000L);
        assertThat(aliased).isEqualTo(estimator.estimate("openai", "example-model", usage(1000L, null, 500L)));
    }

    @Test
    @DisplayName("별칭에 없는 provider 는 그 이름 그대로 찾는다")
    void providerNotInAliasIsLookedUpByItsOwnName() {
        EstimatedCost cost = estimator.estimate("anthropic", "example-model-large", usage(100L, null, 10L));

        assertThat(cost.micros()).isEqualTo(2_250L);
    }

    @Test
    @DisplayName("가격을 찾지 못하면 금액이 비어 있다")
    void amountIsEmptyWhenPriceIsNotFound() {
        EstimatedCost unknownModel = estimator.estimate("openai", "gpt-does-not-exist", usage(1000L, null, 500L));
        EstimatedCost unknownProvider = estimator.estimate("some-vendor", "example-model", usage(1000L, null, 500L));
        EstimatedCost noRates = estimator.estimate("openai", "gpt-free-tool", usage(1000L, null, 500L));

        assertThat(unknownModel).isEqualTo(EstimatedCost.unknown());
        assertThat(unknownProvider).isEqualTo(EstimatedCost.unknown());
        assertThat(noRates).isEqualTo(EstimatedCost.unknown());
        assertThat(unknownModel.micros()).isNull();
        assertThat(unknownModel.pricingVersion()).isNull();
    }

    @Test
    @DisplayName("토큰을 하나도 보고하지 않은 실행은 금액이 비어 있다")
    void amountIsEmptyForRunThatReportedNoTokens() {
        assertThat(estimator.estimate("openai", "example-model", TokenUsage.empty()))
                .isEqualTo(EstimatedCost.unknown());
    }

    @Test
    @DisplayName("카탈로그가 없으면 기동을 막지 않고 금액만 비워 둔다")
    void leavesOnlyAmountEmptyWithoutBlockingStartupWhenNoCatalog() {
        CostEstimator noCatalog = new CostEstimator(new ModelsDevPriceCatalog(pricing("/tmp/no-such-catalog.json")));
        CostEstimator notConfigured = new CostEstimator(new ModelsDevPriceCatalog(pricing("")));

        assertThat(noCatalog.estimate("openai", "example-model", usage(1000L, null, 500L)))
                .isEqualTo(EstimatedCost.unknown());
        assertThat(notConfigured.estimate("openai", "example-model", usage(1000L, null, 500L)))
                .isEqualTo(EstimatedCost.unknown());
    }

    @Test
    @DisplayName("API 경로는 환산액과 실제 청구액이 같다")
    void apiPathConvertedAmountEqualsActualBilledAmount() {
        ExecutionCost cost = estimator.estimate("openai", "example-model", usage(1000L, null, 500L), CostMode.API);

        assertThat(cost.estimatedMicros()).isEqualTo(20_000L);
        assertThat(cost.actualMicros()).isEqualTo(20_000L);
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.pricingVersion()).isEqualTo("models.dev@2026-09-17");
    }

    @Test
    @DisplayName("구독 경로는 환산액은 있고 실제 청구액은 비어 있다")
    void subscriptionPathHasConvertedAmountAndEmptyActualBilledAmount() {
        ExecutionCost cost =
                estimator.estimate("openai", "example-model", usage(1000L, null, 500L), CostMode.SUBSCRIPTION);

        assertThat(cost.estimatedMicros()).isEqualTo(20_000L);
        assertThat(cost.actualMicros()).isNull();
    }

    @Test
    @DisplayName("가격표에 없는 모델은 cost mode 와 무관하게 둘 다 비어 있다")
    void modelNotInPriceTableGivesBothEmptyRegardlessOfCostMode() {
        ExecutionCost apiCost =
                estimator.estimate("openai", "gpt-does-not-exist", usage(1000L, null, 500L), CostMode.API);
        ExecutionCost subscriptionCost =
                estimator.estimate("openai", "gpt-does-not-exist", usage(1000L, null, 500L), CostMode.SUBSCRIPTION);

        assertThat(apiCost).isEqualTo(ExecutionCost.unknown());
        assertThat(subscriptionCost).isEqualTo(ExecutionCost.unknown());
        assertThat(apiCost.estimatedMicros()).isNull();
        assertThat(apiCost.actualMicros()).isNull();
    }

    @Test
    @DisplayName("금액에는 그 가격을 찾은 가격표의 버전을 적는다")
    void amountRecordsVersionOfPriceTableThatFoundThePrice() {
        // 조회한 뒤 가격표가 다시 읽혀 버전이 바뀐 상황이다. 금액에는 가격을 찾은 쪽의 버전이 붙어야 한다.
        ModelPrice price = new ModelPrice(new BigDecimal("5"), new BigDecimal("30"), null, List.of());
        PriceCatalog reloadedBetween = new PriceCatalog() {
            @Override
            public Optional<CatalogPrice> find(String provider, String model) {
                return Optional.of(new CatalogPrice(price, "models.dev@2026-09-17"));
            }

            @Override
            public String version() {
                return "models.dev@2026-09-28";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        EstimatedCost cost = new CostEstimator(reloadedBetween).estimate("openai", "m", usage(1000L, null, 500L));

        assertThat(cost.pricingVersion()).isEqualTo("models.dev@2026-09-17");
    }

    @Test
    @DisplayName("모델 이름이 같아도 provider 별 가격을 쓰고 모르는 provider 는 금액을 비운다")
    void pricesSameModelSeparatelyForEachProvider() {
        PriceCatalog catalog = mock(PriceCatalog.class);
        when(catalog.find("provider-a", "shared-model"))
                .thenReturn(Optional.of(new CatalogPrice(
                        new ModelPrice(new BigDecimal("2"), new BigDecimal("8"), null, List.of()), "a@1")));
        when(catalog.find("provider-b", "shared-model"))
                .thenReturn(Optional.of(new CatalogPrice(
                        new ModelPrice(new BigDecimal("5"), new BigDecimal("30"), null, List.of()), "b@1")));
        CostEstimator costs = new CostEstimator(catalog);
        TokenUsage tokens = usage(1000L, null, 500L);

        assertThat(costs.estimate("provider-a", "shared-model", tokens).micros()).isEqualTo(6_000L);
        assertThat(costs.estimate("provider-b", "shared-model", tokens).micros()).isEqualTo(20_000L);
        assertThat(costs.estimate("provider-b", "shared-model", tokens).pricingVersion()).isEqualTo("b@1");
        assertThat(costs.estimate("provider-c", "shared-model", tokens)).isEqualTo(EstimatedCost.unknown());
    }

    private static TokenUsage usage(Long input, Long cached, Long output) {
        long total = (input == null ? 0 : input) + (output == null ? 0 : output);
        return new TokenUsage(input, cached, output, total);
    }
}
