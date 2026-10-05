package com.bifos.assistant.attention.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 먼저 알리기 판정의 기준값이다. 뜻은 {@code docs/backend/attention.md} 의 「기준값」 이 갖는다.
 *
 * <p>값이 없거나 0 이하면 기본값으로 둔다.
 *
 * @param failureWindow 실패한 turn 을 보이는 기간
 * @param delegatedWindow 끝난 위임을 보이는 기간
 * @param longRunningAfter 도는 위임이 {@code NOW} 가 되는 시간
 * @param dueSoon 기한이 이만큼 남으면 할 일이 {@code NOW} 가 된다
 * @param continueCount 이어서 하기 카드의 항목 상한
 * @param maxItemsPerCard 이어서 하기를 뺀 카드 하나의 항목 상한
 * @param snoozeMax 미루기 기한의 상한. 지금부터 이만큼 뒤까지만 받는다
 * @param eventRetention 지표 사건을 남기는 기간
 */
@Validated
@ConfigurationProperties(prefix = "assistant.attention")
public record AttentionProperties(
        Duration failureWindow,
        Duration delegatedWindow,
        Duration longRunningAfter,
        Duration dueSoon,
        int continueCount,
        int maxItemsPerCard,
        Duration snoozeMax,
        Duration eventRetention) {

    private static final Duration DEFAULT_FAILURE_WINDOW = Duration.ofDays(7);
    private static final Duration DEFAULT_DELEGATED_WINDOW = Duration.ofHours(24);
    private static final Duration DEFAULT_LONG_RUNNING_AFTER = Duration.ofMinutes(30);
    private static final Duration DEFAULT_DUE_SOON = Duration.ofHours(24);
    private static final int DEFAULT_CONTINUE_COUNT = 5;
    private static final int DEFAULT_MAX_ITEMS_PER_CARD = 10;
    private static final Duration DEFAULT_SNOOZE_MAX = Duration.ofDays(8);
    private static final Duration DEFAULT_EVENT_RETENTION = Duration.ofDays(90);

    public AttentionProperties {
        failureWindow = positiveOr(failureWindow, DEFAULT_FAILURE_WINDOW);
        delegatedWindow = positiveOr(delegatedWindow, DEFAULT_DELEGATED_WINDOW);
        longRunningAfter = positiveOr(longRunningAfter, DEFAULT_LONG_RUNNING_AFTER);
        dueSoon = positiveOr(dueSoon, DEFAULT_DUE_SOON);
        if (continueCount <= 0) {
            continueCount = DEFAULT_CONTINUE_COUNT;
        }
        if (maxItemsPerCard <= 0) {
            maxItemsPerCard = DEFAULT_MAX_ITEMS_PER_CARD;
        }
        snoozeMax = positiveOr(snoozeMax, DEFAULT_SNOOZE_MAX);
        eventRetention = positiveOr(eventRetention, DEFAULT_EVENT_RETENTION);
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
