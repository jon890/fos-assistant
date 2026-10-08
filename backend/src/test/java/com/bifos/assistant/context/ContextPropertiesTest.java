package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Memory 신선도 설정의 기본값과 바인딩을 확인한다. */
class ContextPropertiesTest {

    @Test
    @DisplayName("Memory 신선도 기준을 비우면 180일과 빈 collection 기준을 쓴다")
    void usesDefaultsWhenMemoryFreshnessSettingsAreMissing() {
        ContextProperties properties = new ContextProperties(8_000, 4, Duration.ofHours(6), null, null, null, null);

        assertThat(properties.memoryStaleAfter()).isEqualTo(Duration.ofDays(180));
        assertThat(properties.memoryCollectionStaleAfter()).isEmpty();
    }

    @Test
    @DisplayName("개인 사실 구역 예산은 비거나 음수면 2000자이고 0 은 끔으로 남으며 본문 상한은 0 이하면 200자다")
    void usesFactsDefaultsAndKeepsZeroBudgetAsOff() {
        ContextProperties missing = new ContextProperties(8_000, 4, null, null, null, null, null);
        ContextProperties negative = new ContextProperties(8_000, 4, null, null, null, -1, 0);
        ContextProperties off = new ContextProperties(8_000, 4, null, null, null, 0, 150);

        assertThat(missing.factsMaxChars()).isEqualTo(2_000);
        assertThat(missing.factsItemMaxChars()).isEqualTo(200);
        assertThat(negative.factsMaxChars()).isEqualTo(2_000);
        assertThat(negative.factsItemMaxChars()).isEqualTo(200);
        assertThat(off.factsMaxChars()).isZero();
        assertThat(off.factsItemMaxChars()).isEqualTo(150);
    }

    @Test
    @DisplayName("collection 기준은 원본 맵을 바꿔도 함께 바뀌지 않는다")
    void defensivelyCopiesCollectionStaleAfter() {
        Map<String, Duration> source = new HashMap<>();
        source.put("career", Duration.ofDays(30));

        ContextProperties properties =
                new ContextProperties(8_000, 4, Duration.ofHours(6), Duration.ofDays(180), source, null, null);
        source.put("career", Duration.ofDays(90));
        source.put("home", Duration.ofDays(7));

        assertThat(properties.memoryCollectionStaleAfter()).containsExactly(Map.entry("career", Duration.ofDays(30)));
    }

    @Test
    @DisplayName("Spring Binder가 기본 기준과 collection별 기간을 Duration으로, 개인 사실 구역 값을 수로 묶는다")
    void bindsMemoryFreshnessDurations() {
        ContextProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                        "assistant.context.max-chars", "8000",
                        "assistant.context.index-budget-ratio", "4",
                        "assistant.context.result-stale-after", "6h",
                        "assistant.context.memory-stale-after", "180d",
                        "assistant.context.memory-collection-stale-after.career", "30d",
                        "assistant.context.facts-max-chars", "0",
                        "assistant.context.facts-item-max-chars", "120")))
                .bind("assistant.context", Bindable.of(ContextProperties.class))
                .orElseThrow(() -> new IllegalStateException("context properties did not bind"));

        assertThat(properties.memoryStaleAfter()).isEqualTo(Duration.ofDays(180));
        assertThat(properties.memoryCollectionStaleAfter()).containsExactly(Map.entry("career", Duration.ofDays(30)));
        assertThat(properties.factsMaxChars()).isZero();
        assertThat(properties.factsItemMaxChars()).isEqualTo(120);
    }
}
