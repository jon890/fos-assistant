package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.attention.application.AttentionJudge;
import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionCard;
import com.bifos.assistant.attention.application.model.AttentionConfidence;
import com.bifos.assistant.attention.application.model.AttentionControl;
import com.bifos.assistant.attention.application.model.AttentionItem;
import com.bifos.assistant.attention.application.model.AttentionProblem;
import com.bifos.assistant.attention.application.model.AttentionView;
import com.bifos.assistant.attention.application.model.CardStatus;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 후보와 제어로 카드 다섯을 정하는 규칙을 본다. 규칙은 {@code docs/backend/attention.md} 의 「억제 신호」 와 「API」 다. */
class AttentionJudgeTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");
    private static final int MAX_ITEMS = 10;
    private static final int CONTINUE_COUNT = 5;

    private final AttentionJudge judge = new AttentionJudge();

    @Test
    @DisplayName("실패 카드에 남은 대화는 이어서 하기에서 중복으로 빠지고 실패 항목은 NOW 다")
    void dropsDuplicateFromLaterCardAndKeepsFailureNow() {
        AttentionCandidate failure =
                candidate(CardKey.FAILURES, "conversation:a", "s1", true, AttentionConfidence.CONTROL_PLANE);
        AttentionCandidate recent =
                candidate(CardKey.CONTINUE, "conversation:a", "s2", false, AttentionConfidence.CONTROL_PLANE);

        List<AttentionCard> cards =
                judge(Map.of(CardKey.FAILURES, List.of(failure), CardKey.CONTINUE, List.of(recent)));

        assertThat(card(cards, CardKey.FAILURES).items())
                .extracting(AttentionItem::itemKey, AttentionItem::level)
                .containsExactly(tuple("conversation:a", AttentionLevel.NOW));
        assertThat(card(cards, CardKey.CONTINUE).items()).isEmpty();
    }

    @Test
    @DisplayName("모델이 낸 후보는 NOW 조건을 채워도 LATER 다")
    void keepsModelInferredCandidateLater() {
        AttentionCandidate proposal =
                candidate(CardKey.NEEDS_ME, "memory:1", "s", true, AttentionConfidence.MODEL_INFERRED);

        List<AttentionCard> cards = judge(Map.of(CardKey.NEEDS_ME, List.of(proposal)));

        assertThat(card(cards, CardKey.NEEDS_ME).items())
                .extracting(AttentionItem::level)
                .containsExactly(AttentionLevel.LATER);
        assertThat(card(cards, CardKey.NEEDS_ME).nowCount()).isZero();
    }

    @Test
    @DisplayName("먼저 다룰 문제 후보의 판정 값은 항목에 그대로 실리고 다른 후보의 값은 null 이다")
    void carriesProblemOntoItem() {
        AttentionProblem problem = new AttentionProblem(7L, "SURFACE", "공고 요건을 정리한다");
        AttentionCandidate surfaced = new AttentionCandidate(
                CardKey.NEEDS_ME,
                "autonomy_decision:7",
                "s",
                AttentionTrigger.PROBLEM_SURFACED,
                false,
                false,
                List.of(),
                AttentionConfidence.MODEL_INFERRED,
                "지원 마감이 내일이다",
                null,
                null,
                NOW,
                List.of(),
                null,
                null,
                null,
                null,
                problem);
        AttentionCandidate plain =
                candidate(CardKey.NEEDS_ME, "memory:1", "s", false, AttentionConfidence.MODEL_INFERRED);

        List<AttentionCard> cards = judge(Map.of(CardKey.NEEDS_ME, List.of(surfaced, plain)));

        assertThat(card(cards, CardKey.NEEDS_ME).items())
                .extracting(AttentionItem::itemKey, AttentionItem::problem)
                .containsExactlyInAnyOrder(tuple("autonomy_decision:7", problem), tuple("memory:1", null));
    }

    @Test
    @DisplayName("해결된 후보는 응답에 없다")
    void dropsResolvedCandidate() {
        AttentionCandidate resolved = new AttentionCandidate(
                CardKey.FAILURES,
                "conversation:r",
                "s",
                AttentionTrigger.EXECUTION_FAILED,
                true,
                true,
                List.of(),
                AttentionConfidence.CONTROL_PLANE,
                "주간 장보기 목록 정리",
                null,
                null,
                NOW,
                List.of(),
                null,
                null,
                null);

        List<AttentionCard> cards = judge(Map.of(CardKey.FAILURES, List.of(resolved)));

        assertThat(card(cards, CardKey.FAILURES).items()).isEmpty();
    }

    @Test
    @DisplayName("같은 카드에서 같은 상태를 숨기면 빠지고 상태가 바뀌면 다시 보인다")
    void hidesSameStateAndShowsChangedState() {
        AttentionControl hidden = new AttentionControl(CardKey.FAILURES, "conversation:h", "old", null);
        AttentionCandidate same =
                candidate(CardKey.FAILURES, "conversation:h", "old", true, AttentionConfidence.CONTROL_PLANE);
        AttentionCandidate changed =
                candidate(CardKey.FAILURES, "conversation:h", "new", true, AttentionConfidence.CONTROL_PLANE);

        List<AttentionCard> sameCards = judge(Map.of(CardKey.FAILURES, List.of(same)), List.of(hidden));
        List<AttentionCard> changedCards = judge(Map.of(CardKey.FAILURES, List.of(changed)), List.of(hidden));

        assertThat(card(sameCards, CardKey.FAILURES).items()).isEmpty();
        assertThat(card(changedCards, CardKey.FAILURES).items())
                .extracting(AttentionItem::stateKey)
                .containsExactly("new");
    }

    @Test
    @DisplayName("실패 카드에 건 숨기기는 이어서 하기 카드의 같은 대화에 걸리지 않는다")
    void keepsControlPerCard() {
        AttentionControl hidden = new AttentionControl(CardKey.FAILURES, "conversation:c", "fail", null);
        AttentionCandidate failure =
                candidate(CardKey.FAILURES, "conversation:c", "fail", true, AttentionConfidence.CONTROL_PLANE);
        AttentionCandidate recent =
                candidate(CardKey.CONTINUE, "conversation:c", "recent", false, AttentionConfidence.CONTROL_PLANE);

        List<AttentionCard> cards =
                judge(Map.of(CardKey.FAILURES, List.of(failure), CardKey.CONTINUE, List.of(recent)), List.of(hidden));

        assertThat(card(cards, CardKey.FAILURES).items()).isEmpty();
        assertThat(card(cards, CardKey.CONTINUE).items())
                .extracting(AttentionItem::itemKey)
                .containsExactly("conversation:c");
    }

    @Test
    @DisplayName("미룬 기한이 지금 뒤인 항목만 빠진다")
    void dropsOnlyItemSnoozedUntilLater() {
        AttentionControl future =
                new AttentionControl(CardKey.NEEDS_ME, "connector_action:f", null, NOW.plusSeconds(60));
        AttentionControl past =
                new AttentionControl(CardKey.NEEDS_ME, "connector_action:p", null, NOW.minusSeconds(60));

        List<AttentionCard> cards = judge(
                Map.of(
                        CardKey.NEEDS_ME,
                        List.of(
                                candidate(
                                        CardKey.NEEDS_ME,
                                        "connector_action:f",
                                        "s",
                                        true,
                                        AttentionConfidence.CONTROL_PLANE),
                                candidate(
                                        CardKey.NEEDS_ME,
                                        "connector_action:p",
                                        "s",
                                        true,
                                        AttentionConfidence.CONTROL_PLANE))),
                List.of(future, past));

        assertThat(card(cards, CardKey.NEEDS_ME).items())
                .extracting(AttentionItem::itemKey)
                .containsExactly("connector_action:p");
    }

    @Test
    @DisplayName("맡긴 일 카드에만 NOW 가 있으면 그 카드가 맨 앞이고 나머지는 고정 순서다")
    void putsCardWithNowFirst() {
        List<AttentionCard> cards = judge(Map.of(
                CardKey.DELEGATED,
                        List.of(candidate(
                                CardKey.DELEGATED, "execution:1", "s", true, AttentionConfidence.CONTROL_PLANE)),
                CardKey.NEEDS_ME,
                        List.of(candidate(
                                CardKey.NEEDS_ME, "memory:1", "s", false, AttentionConfidence.MODEL_INFERRED))));

        assertThat(cards)
                .extracting(AttentionCard::key)
                .containsExactly(
                        CardKey.DELEGATED, CardKey.FAILURES, CardKey.NEEDS_ME, CardKey.CONTINUE, CardKey.REPORTS);
    }

    @Test
    @DisplayName("한 카드의 후보 12개는 상한 10 으로 잘리고 moreCount 가 2 다")
    void capsItemsAndCountsRest() {
        List<AttentionCard> cards = judge(Map.of(CardKey.DELEGATED, many(CardKey.DELEGATED, 12, false)));

        AttentionCard delegated = card(cards, CardKey.DELEGATED);
        assertThat(delegated.items()).hasSize(10);
        assertThat(delegated.moreCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("NOW 와 LATER 가 섞인 후보 12개는 NOW 먼저, 최근 순, 같은 시각은 itemKey 글 순으로 앞 10개만 남는다")
    void keepsFirstTenInNowRecentAndKeyOrder() {
        List<AttentionCandidate> mixed = List.of(
                timed("later:06", false, NOW.minus(Duration.ofMinutes(7))),
                timed("now:d", true, NOW.minus(Duration.ofMinutes(30))),
                timed("later:01", false, NOW),
                timed("later:08", false, NOW.minus(Duration.ofMinutes(9))),
                timed("now:b", true, NOW.minus(Duration.ofMinutes(1))),
                timed("later:03", false, NOW.minus(Duration.ofMinutes(3))),
                timed("later:07", false, NOW.minus(Duration.ofMinutes(8))),
                timed("now:a", true, NOW.minus(Duration.ofMinutes(1))),
                timed("later:02", false, NOW.minus(Duration.ofMinutes(2))),
                timed("now:c", true, NOW.minus(Duration.ofMinutes(5))),
                timed("later:05", false, NOW.minus(Duration.ofMinutes(6))),
                timed("later:04", false, NOW.minus(Duration.ofMinutes(4))));

        AttentionCard delegated = card(judge(Map.of(CardKey.DELEGATED, mixed)), CardKey.DELEGATED);

        assertThat(delegated.items())
                .extracting(AttentionItem::itemKey)
                .containsExactly(
                        "now:a",
                        "now:b",
                        "now:c",
                        "now:d",
                        "later:01",
                        "later:02",
                        "later:03",
                        "later:04",
                        "later:05",
                        "later:06");
        assertThat(delegated.moreCount()).isEqualTo(2);
        assertThat(delegated.nowCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("NOW 후보 12개는 항목 10개로 잘려도 카드 nowCount 는 12 이고 맨 위 건수는 카드 nowCount 의 합이다")
    void countsNowBeforeCapAndSumsCards() {
        List<AttentionCard> cards = judge(Map.of(
                CardKey.FAILURES, many(CardKey.FAILURES, 12, true),
                CardKey.DELEGATED, many(CardKey.DELEGATED, 1, true)));

        AttentionView view = AttentionView.of(NOW, cards);

        assertThat(card(cards, CardKey.FAILURES).items()).hasSize(10);
        assertThat(card(cards, CardKey.FAILURES).nowCount()).isEqualTo(12);
        assertThat(view.nowCount()).isEqualTo(13);
    }

    @Test
    @DisplayName("이어서 하기 후보 8개는 continueCount 5 로 잘리고 moreCount 가 3 이다")
    void capsContinueCardWithContinueCount() {
        List<AttentionCard> cards = judge(Map.of(CardKey.CONTINUE, many(CardKey.CONTINUE, 8, false)));

        AttentionCard continueCard = card(cards, CardKey.CONTINUE);
        assertThat(continueCard.items()).hasSize(5);
        assertThat(continueCard.moreCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("읽지 못한 카드만 UNAVAILABLE 이고 나머지 카드는 그대로다")
    void marksOnlyUnavailableCard() {
        Map<CardKey, List<AttentionCandidate>> candidates = new EnumMap<>(CardKey.class);
        candidates.put(CardKey.FAILURES, many(CardKey.FAILURES, 1, true));
        candidates.put(CardKey.CONTINUE, many(CardKey.CONTINUE, 2, false));

        List<AttentionCard> cards =
                judge.judge(candidates, Set.of(CardKey.NEEDS_ME), List.of(), NOW, MAX_ITEMS, CONTINUE_COUNT);

        assertThat(card(cards, CardKey.NEEDS_ME).status()).isEqualTo(CardStatus.UNAVAILABLE);
        assertThat(card(cards, CardKey.NEEDS_ME).items()).isEmpty();
        assertThat(card(cards, CardKey.FAILURES).status()).isEqualTo(CardStatus.OK);
        assertThat(card(cards, CardKey.FAILURES).items()).hasSize(1);
        assertThat(card(cards, CardKey.CONTINUE).items()).hasSize(2);
        assertThat(cards).hasSize(5);
    }

    private List<AttentionCard> judge(Map<CardKey, List<AttentionCandidate>> candidates) {
        return judge(candidates, List.of());
    }

    private List<AttentionCard> judge(
            Map<CardKey, List<AttentionCandidate>> candidates, List<AttentionControl> controls) {
        return judge.judge(candidates, Set.of(), controls, NOW, MAX_ITEMS, CONTINUE_COUNT);
    }

    private static AttentionCard card(List<AttentionCard> cards, CardKey key) {
        return cards.stream()
                .filter(card -> card.key() == key)
                .findFirst()
                .orElseThrow(() -> new AssertionError("카드 " + key + " 가 응답에 없다: " + cards));
    }

    /** 시각이 1분씩 앞선 후보를 {@code count} 개 만든다. */
    private static List<AttentionCandidate> many(CardKey card, int count, boolean nowSignal) {
        return IntStream.range(0, count)
                .mapToObj(index -> new AttentionCandidate(
                        card,
                        card.name().toLowerCase(Locale.ROOT) + ":" + index,
                        "s",
                        AttentionTrigger.CONVERSATION_RECENT,
                        false,
                        nowSignal,
                        List.of(),
                        AttentionConfidence.CONTROL_PLANE,
                        "주간 장보기 목록 정리",
                        null,
                        null,
                        NOW.minus(Duration.ofMinutes(index)),
                        List.of(),
                        null,
                        null,
                        null))
                .toList();
    }

    /** 맡긴 일 카드에 시각을 정한 후보 하나를 만든다. */
    private static AttentionCandidate timed(String itemKey, boolean nowSignal, Instant at) {
        return new AttentionCandidate(
                CardKey.DELEGATED,
                itemKey,
                "s",
                AttentionTrigger.DELEGATION_RUNNING,
                false,
                nowSignal,
                List.of(),
                AttentionConfidence.CONTROL_PLANE,
                "여행 일정 짜기",
                null,
                null,
                at,
                List.of(),
                null,
                null,
                null);
    }

    private static AttentionCandidate candidate(
            CardKey card, String itemKey, String stateKey, boolean nowSignal, AttentionConfidence confidence) {
        return new AttentionCandidate(
                card,
                itemKey,
                stateKey,
                AttentionTrigger.EXECUTION_FAILED,
                false,
                nowSignal,
                List.of(),
                confidence,
                "주간 장보기 목록 정리",
                null,
                null,
                NOW,
                List.of(),
                null,
                null,
                null);
    }
}
