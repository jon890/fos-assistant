package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionCard;
import com.bifos.assistant.attention.application.model.AttentionChannel;
import com.bifos.assistant.attention.application.model.AttentionConfidence;
import com.bifos.assistant.attention.application.model.AttentionControl;
import com.bifos.assistant.attention.application.model.AttentionItem;
import com.bifos.assistant.attention.application.model.AttentionWhy;
import com.bifos.assistant.attention.application.model.CardStatus;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.CardKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 후보와 사용자 제어를 받아 카드 다섯을 정한다. 원래 기록을 읽지 않고 상태가 없다.
 *
 * <p>억제, 중복, {@code NOW} 판정, 카드 안의 순서와 상한, 카드 순서를 여기 모은다. 규칙은 {@code docs/backend/attention.md} 의
 * 「억제 신호」 와 「API」 가 갖는다.
 */
@Component
public class AttentionJudge {

    /** 카드 안의 순서. {@code NOW} 먼저, 그다음 최근 순, 같으면 항목 열쇠 글 순이다. */
    private static final Comparator<AttentionItem> ITEM_ORDER = Comparator.comparing(
                    (AttentionItem item) -> item.level() != AttentionLevel.NOW)
            .thenComparing(AttentionItem::at, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AttentionItem::itemKey);

    /**
     * @param candidates 카드마다 모은 후보
     * @param unavailable 원래 기록을 읽지 못한 카드. 항목 없이 {@code UNAVAILABLE} 로 낸다
     * @param controls 요청자의 숨기기와 미루기
     * @param maxItemsPerCard 이어서 하기를 뺀 카드의 항목 상한
     * @param continueCount 이어서 하기 카드의 항목 상한
     * @return 카드 다섯. {@code NOW} 항목이 있는 카드가 먼저이고, 같은 무리 안에서는 {@link CardKey} 선언 순서다
     */
    public List<AttentionCard> judge(
            Map<CardKey, List<AttentionCandidate>> candidates,
            Set<CardKey> unavailable,
            List<AttentionControl> controls,
            Instant now,
            int maxItemsPerCard,
            int continueCount) {
        Set<String> keptKeys = new HashSet<>();
        List<AttentionCard> withNow = new ArrayList<>();
        List<AttentionCard> withoutNow = new ArrayList<>();
        for (CardKey card : CardKey.values()) {
            AttentionCard judged;
            if (unavailable.contains(card)) {
                judged = new AttentionCard(card, CardStatus.UNAVAILABLE, 0, 0, List.of());
            } else {
                int limit = card == CardKey.CONTINUE ? continueCount : maxItemsPerCard;
                judged = judgeCard(card, candidates.getOrDefault(card, List.of()), controls, now, limit, keptKeys);
            }
            (judged.nowCount() > 0 ? withNow : withoutNow).add(judged);
        }
        List<AttentionCard> ordered = new ArrayList<>(withNow);
        ordered.addAll(withoutNow);
        return List.copyOf(ordered);
    }

    /** 한 카드의 후보를 억제 신호로 거르고, 판정을 붙여 줄 세운 뒤 상한으로 자른다. 남은 열쇠를 {@code keptKeys} 에 더한다. */
    private static AttentionCard judgeCard(
            CardKey card,
            List<AttentionCandidate> candidates,
            List<AttentionControl> controls,
            Instant now,
            int limit,
            Set<String> keptKeys) {
        List<AttentionItem> items = new ArrayList<>();
        for (AttentionCandidate candidate : candidates) {
            if (suppressed(card, candidate, controls, now, keptKeys)) {
                continue;
            }
            items.add(item(candidate));
        }
        items.forEach(item -> keptKeys.add(item.itemKey()));
        items.sort(ITEM_ORDER);
        int nowCount = (int) items.stream()
                .filter(item -> item.level() == AttentionLevel.NOW)
                .count();
        int moreCount = Math.max(0, items.size() - limit);
        List<AttentionItem> shown = List.copyOf(items.subList(0, Math.min(limit, items.size())));
        return new AttentionCard(card, CardStatus.OK, nowCount, moreCount, shown);
    }

    /** 억제 신호를 위에서부터 본다. 숨김, 미룸, 해결됨, 앞선 카드와의 중복이다. */
    private static boolean suppressed(
            CardKey card,
            AttentionCandidate candidate,
            List<AttentionControl> controls,
            Instant now,
            Set<String> keptKeys) {
        for (AttentionControl control : controls) {
            if (control.card() != card || !control.itemKey().equals(candidate.itemKey())) {
                continue;
            }
            if (control.stateKey() != null && control.stateKey().equals(candidate.stateKey())) {
                return true;
            }
            if (control.snoozedUntil() != null && control.snoozedUntil().isAfter(now)) {
                return true;
            }
        }
        return candidate.resolved() || keptKeys.contains(candidate.itemKey());
    }

    private static AttentionItem item(AttentionCandidate candidate) {
        boolean now = candidate.nowSignal() && candidate.confidence() != AttentionConfidence.MODEL_INFERRED;
        return new AttentionItem(
                candidate.itemKey(),
                candidate.stateKey(),
                now ? AttentionLevel.NOW : AttentionLevel.LATER,
                AttentionChannel.IN_APP,
                candidate.trigger(),
                candidate.title(),
                candidate.conversationId(),
                candidate.agentName(),
                candidate.at(),
                new AttentionWhy(candidate.trigger(), candidate.signals(), candidate.confidence(), candidate.sources()),
                candidate.execution(),
                candidate.actionId(),
                candidate.followUp(),
                candidate.report());
    }
}
