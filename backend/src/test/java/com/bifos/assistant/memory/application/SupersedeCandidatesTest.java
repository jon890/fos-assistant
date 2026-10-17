package com.bifos.assistant.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.memory.application.dto.MemoryLinkItem;
import com.bifos.assistant.memory.config.MemoryLinkProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 비슷하지만 값이 다른 쌍만 대체 후보가 되고, 나중에 고친 항목이 새 항목이 되는지 확인한다. */
class SupersedeCandidatesTest {

    private static final Instant EARLIER = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-10T00:00:00Z");
    private static final float[] SAME = {1f, 0f, 0f};
    private static final float[] CLOSE = {0.95f, 0.31f, 0f};
    private static final float[] FAR = {0f, 1f, 0f};

    private final SupersedeCandidates candidates = new SupersedeCandidates(MemoryLinkProperties.defaults());


    @Test
    @DisplayName("부정 표지나 숫자가 다른 비슷한 글은 후보이고 나중에 고친 항목이 새 항목이다")
    void findsSimilarPairsWithDifferentValues() {
        MemoryLinkItem allergy = item(1L, LATER, "딸 땅콩 알레르기\n딸은 땅콩 알레르기가 없다", SAME);
        MemoryLinkItem oldAllergy = item(2L, EARLIER, "딸 땅콩 알레르기\n딸은 땅콩 알레르기가 있다", SAME);
        MemoryLinkItem parking = item(3L, EARLIER, "주차 자리\n차는 3층에 세운다", FAR);

        assertThat(candidates.find(oldAllergy, List.of(allergy, parking)))
            .extracting(c -> c.newer().id(), c -> c.older().id())
            .containsExactly(tuple(1L, 2L));

        MemoryLinkItem newParking = item(4L, LATER, "주차 자리\n차는 5층에 세운다", FAR);
        assertThat(candidates.find(newParking, List.of(parking)))
            .extracting(c -> c.newer().id(), c -> c.older().id())
            .containsExactly(tuple(4L, 3L));
    }


    @Test
    @DisplayName("값이 같은 비슷한 글과 유사도가 낮은 글은 후보가 아니다")
    void ignoresSameValuesAndDistantPairs() {
        MemoryLinkItem target = item(1L, LATER, "주차 자리\n차는 3층에 세운다", SAME);
        MemoryLinkItem sameValue = item(2L, EARLIER, "주차 위치\n차는 늘 3층에 세운다", SAME);
        MemoryLinkItem distant = item(3L, EARLIER, "주차 자리\n차는 5층에 세운다", FAR);

        assertThat(candidates.find(target, List.of(sameValue, distant))).isEmpty();
    }


    @Test
    @DisplayName("후보는 유사도가 높은 것부터 항목마다 두 건까지다")
    void keepsTopTwoCandidates() {
        MemoryLinkItem target = item(1L, LATER, "학원\n월요일 4시", SAME);
        List<MemoryLinkItem> peers = List.of(
            item(2L, EARLIER, "학원\n화요일 4시", CLOSE),
            item(3L, EARLIER, "학원\n수요일 4시", SAME),
            item(4L, EARLIER, "학원\n목요일 4시", SAME));

        assertThat(candidates.find(target, peers)).extracting(c -> c.older().id()).containsExactly(3L, 4L);
    }


    @Test
    @DisplayName("숫자의 천 단위 쉼표와 날짜 낱말을 값으로 견준다")
    void comparesNumbersAndDateWords() {
        assertThat(SupersedeCandidates.valuesDiffer("월세 1,200,000원", "월세 1200000원")).isFalse();
        assertThat(SupersedeCandidates.valuesDiffer("병원은 화요일", "병원은 목요일")).isTrue();
        assertThat(SupersedeCandidates.valuesDiffer("안경을 쓴다", "안경을 늘 쓴다")).isFalse();
    }


    static MemoryLinkItem item(Long id, Instant updatedAt, String text, float[] vector) {
        return new MemoryLinkItem(id, updatedAt, text, vector);
    }
}
