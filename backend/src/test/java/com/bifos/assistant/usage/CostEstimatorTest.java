package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.usage.application.CostEstimator;
import com.bifos.assistant.usage.domain.EstimatedCost;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.infra.ModelsDevPriceCatalog;
import com.bifos.assistant.usage.infra.PricingProperties;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 구독제로 돌고 있어도 화면이 금액을 보이려면 이 환산이 맞아야 한다.
 *
 * <p>표본 카탈로그는 models.dev 가 실제로 내려 주는 모양을 그대로 줄인 것이다. {@code gpt-5.5} 항목은
 * 단가와 구간까지 실제 값이다.
 */
class CostEstimatorTest {

    private static final String CODEX_PROVIDER = "openai-codex";

    private static Path catalogFile;
    private static CostEstimator estimator;

    @BeforeAll
    static void loadCatalog() throws URISyntaxException, IOException {
        catalogFile =
                Path.of(CostEstimatorTest.class.getResource("/pricing/models-dev-sample.json").toURI());
        // pricing_version 은 파일의 수정 시각에서 나온다. 검사에서 그 날짜를 못 박는다.
        Files.setLastModifiedTime(catalogFile, FileTime.from(Instant.parse("2026-09-17T04:00:00Z")));
        estimator = new CostEstimator(new ModelsDevPriceCatalog(new PricingProperties(catalogFile.toString())));
    }

    @Test
    void 카탈로그에서_가격을_찾아_금액을_계산한다() {
        // 입력 1000 × 5 + 출력 500 × 30 = 20000 마이크로 달러
        EstimatedCost cost = estimator.estimate("openai", "gpt-5.5", usage(1000L, null, 500L));

        assertThat(cost.micros()).isEqualTo(20_000L);
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.pricingVersion()).isEqualTo("models.dev@2026-09-17");
    }

    @Test
    void 캐시된_입력은_캐시_단가로_세고_나머지만_입력_단가로_센다() {
        // 입력 1000 중 800 이 캐시다. 200 × 5 + 800 × 0.5 + 500 × 30 = 16400
        EstimatedCost cost = estimator.estimate("openai", "gpt-5.5", usage(1000L, 800L, 500L));

        assertThat(cost.micros()).isEqualTo(16_400L);
    }

    @Test
    void 캐시_단가가_없는_모델은_캐시_토큰도_입력_단가로_센다() {
        // 할인한다는 근거가 없으므로 공짜로 떨어뜨리지 않는다. 1000 × 2 + 100 × 8 = 2800
        EstimatedCost cost = estimator.estimate("openai", "gpt-flat", usage(1000L, 400L, 100L));

        assertThat(cost.micros()).isEqualTo(2_800L);
    }

    @Test
    void 구간_경계를_넘으면_그_구간의_단가를_쓴다() {
        // 경계는 272000 이다. 300000 × 10 + 1000 × 45 = 3045000
        EstimatedCost cost = estimator.estimate("openai", "gpt-5.5", usage(300_000L, null, 1_000L));

        assertThat(cost.micros()).isEqualTo(3_045_000L);
    }

    @Test
    void 구간_경계와_같으면_아직_기본_단가를_쓴다() {
        // 272000 × 5 = 1360000. 경계를 넘을 때만 올라간다
        EstimatedCost cost = estimator.estimate("openai", "gpt-5.5", usage(272_000L, null, 0L));

        assertThat(cost.micros()).isEqualTo(1_360_000L);
    }

    @Test
    void 경계값을_적지_않은_옛_항목은_20만_토큰을_경계로_본다() {
        // gpt-legacy-band 는 context_over_200k 만 적어 두었다. 250000 × 6 + 10 × 12 = 1500120
        EstimatedCost cost = estimator.estimate("openai", "gpt-legacy-band", usage(250_000L, null, 10L));

        assertThat(cost.micros()).isEqualTo(1_500_120L);
    }

    @Test
    void provider_별칭이_동작한다() {
        // 바인딩은 openai-codex 를 적지만 카탈로그는 그 모델을 openai 아래에 둔다
        EstimatedCost aliased = estimator.estimate(CODEX_PROVIDER, "gpt-5.5", usage(1000L, null, 500L));

        assertThat(aliased.micros()).isEqualTo(20_000L);
        assertThat(aliased).isEqualTo(estimator.estimate("openai", "gpt-5.5", usage(1000L, null, 500L)));
    }

    @Test
    void 별칭에_없는_provider_는_그_이름_그대로_찾는다() {
        EstimatedCost cost = estimator.estimate("anthropic", "claude-opus-5", usage(100L, null, 10L));

        assertThat(cost.micros()).isEqualTo(2_250L);
    }

    @Test
    void 가격을_찾지_못하면_금액이_비어_있다() {
        EstimatedCost unknownModel = estimator.estimate("openai", "gpt-does-not-exist", usage(1000L, null, 500L));
        EstimatedCost unknownProvider = estimator.estimate("some-vendor", "gpt-5.5", usage(1000L, null, 500L));
        EstimatedCost noRates = estimator.estimate("openai", "gpt-free-tool", usage(1000L, null, 500L));

        assertThat(unknownModel).isEqualTo(EstimatedCost.unknown());
        assertThat(unknownProvider).isEqualTo(EstimatedCost.unknown());
        assertThat(noRates).isEqualTo(EstimatedCost.unknown());
        assertThat(unknownModel.micros()).isNull();
        assertThat(unknownModel.pricingVersion()).isNull();
    }

    @Test
    void 토큰을_하나도_보고하지_않은_실행은_금액이_비어_있다() {
        assertThat(estimator.estimate("openai", "gpt-5.5", TokenUsage.empty()))
                .isEqualTo(EstimatedCost.unknown());
    }

    @Test
    void 카탈로그가_없으면_기동을_막지_않고_금액만_비워_둔다() {
        CostEstimator noCatalog =
                new CostEstimator(new ModelsDevPriceCatalog(new PricingProperties("/tmp/no-such-catalog.json")));
        CostEstimator notConfigured = new CostEstimator(new ModelsDevPriceCatalog(new PricingProperties("")));

        assertThat(noCatalog.estimate("openai", "gpt-5.5", usage(1000L, null, 500L)))
                .isEqualTo(EstimatedCost.unknown());
        assertThat(notConfigured.estimate("openai", "gpt-5.5", usage(1000L, null, 500L)))
                .isEqualTo(EstimatedCost.unknown());
    }

    @Test
    void API_경로는_환산액과_실제_청구액이_같다() {
        ExecutionCost cost = estimator.estimate("openai", "gpt-5.5", usage(1000L, null, 500L), CostMode.API);

        assertThat(cost.estimatedMicros()).isEqualTo(20_000L);
        assertThat(cost.actualMicros()).isEqualTo(20_000L);
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.pricingVersion()).isEqualTo("models.dev@2026-09-17");
    }

    @Test
    void 구독_경로는_환산액은_있고_실제_청구액은_비어_있다() {
        ExecutionCost cost =
                estimator.estimate("openai", "gpt-5.5", usage(1000L, null, 500L), CostMode.SUBSCRIPTION);

        assertThat(cost.estimatedMicros()).isEqualTo(20_000L);
        assertThat(cost.actualMicros()).isNull();
    }

    @Test
    void 가격표에_없는_모델은_cost_mode_와_무관하게_둘_다_비어_있다() {
        ExecutionCost apiCost =
                estimator.estimate("openai", "gpt-does-not-exist", usage(1000L, null, 500L), CostMode.API);
        ExecutionCost subscriptionCost =
                estimator.estimate(
                        "openai", "gpt-does-not-exist", usage(1000L, null, 500L), CostMode.SUBSCRIPTION);

        assertThat(apiCost).isEqualTo(ExecutionCost.unknown());
        assertThat(subscriptionCost).isEqualTo(ExecutionCost.unknown());
        assertThat(apiCost.estimatedMicros()).isNull();
        assertThat(apiCost.actualMicros()).isNull();
    }

    private static TokenUsage usage(Long input, Long cached, Long output) {
        long total = (input == null ? 0 : input) + (output == null ? 0 : output);
        return new TokenUsage(input, cached, output, total);
    }
}
