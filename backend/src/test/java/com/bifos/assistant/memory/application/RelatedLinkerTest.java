package com.bifos.assistant.memory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.application.dto.MemoryLinkItem;
import com.bifos.assistant.memory.config.MemoryLinkProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 서로의 상위 k 안에 드는 쌍만 자동 RELATED 후보가 되는지 확인한다. */
class RelatedLinkerTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    private final MemoryLinkProperties properties = new MemoryLinkProperties(null, null, 0.7, 1, null);
    private final RelatedLinker linker = new RelatedLinker(properties, new SupersedeCandidates(properties));


    @Test
    @DisplayName("서로 가장 가까운 쌍만 맺고 한쪽만 가까운 쌍은 맺지 않는다")
    void linksOnlyMutualNearest() {
        MemoryLinkItem trip = item(1L, "여름 여행\n제주에 간다", 1f, 0f);
        MemoryLinkItem flight = item(2L, "비행기\n제주 가는 표를 샀다", 0.9f, 0.1f);
        MemoryLinkItem hotel = item(3L, "숙소\n제주 숙소를 잡았다", 0.95f, 0.05f);

        // hotel 의 가장 가까운 항목은 trip 이고 trip 의 가장 가까운 항목도 hotel 이다. flight 의 가장 가까운 항목은 hotel 이지만 hotel 은 flight 를 고르지 않는다
        assertThat(linker.find(trip, List.of(flight, hotel))).extracting(pair -> pair.peer().id()).containsExactly(3L);
        assertThat(linker.find(flight, List.of(trip, hotel))).isEmpty();
    }


    @Test
    @DisplayName("유사도가 아래 값보다 낮거나 대체 후보인 쌍은 맺지 않는다")
    void skipsDistantAndSupersedePairs() {
        MemoryLinkItem a = item(1L, "주차 자리\n차는 3층에 세운다", 1f, 0f);
        MemoryLinkItem replaced = item(2L, "주차 자리\n차는 5층에 세운다", 1f, 0f);
        MemoryLinkItem distant = item(3L, "저녁 메뉴\n김치찌개", 0f, 1f);

        assertThat(linker.find(a, List.of(replaced))).isEmpty();
        assertThat(linker.find(a, List.of(distant))).isEmpty();
    }


    private static MemoryLinkItem item(Long id, String text, float x, float y) {
        return new MemoryLinkItem(id, AT, text, new float[] {x, y});
    }
}
