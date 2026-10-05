package com.bifos.assistant.attention.application.model;

import java.time.Instant;
import java.util.List;

/**
 * 판정 한 번의 결과다.
 *
 * @param readAt 판정 시각
 * @param nowCount 카드 {@code nowCount} 의 합
 * @param cards 카드 다섯. {@code NOW} 가 있는 카드가 먼저다
 */
public record AttentionView(Instant readAt, int nowCount, List<AttentionCard> cards) {

    /** 카드 {@code nowCount} 의 합을 맨 위 건수로 둔다. 화면의 모든 건수가 이 한 가지 셈을 따른다. */
    public static AttentionView of(Instant readAt, List<AttentionCard> cards) {
        return new AttentionView(
                readAt, cards.stream().mapToInt(AttentionCard::nowCount).sum(), cards);
    }
}
