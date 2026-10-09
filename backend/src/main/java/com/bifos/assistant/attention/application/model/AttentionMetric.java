package com.bifos.assistant.attention.application.model;

import com.bifos.assistant.attention.domain.type.AttentionTrigger;

/**
 * 한 {@code trigger} 의 지표 한 줄이다. 셈은 {@code docs/features/attention.md} 의 「지표(먼저 알리기와 지금 화면의 판정)」 가 갖는다.
 *
 * <p>항목 하나는 같은 사용자의 같은 {@code (itemKey, stateKey)} 다. 제목, 항목 열쇠, 사용자 번호를 담지 않는다.
 *
 * @param shown {@code SHOWN} 이 있는 항목 수
 * @param hidden 그 가운데 {@code HIDDEN} 이 있는 수
 * @param snoozed 그 가운데 {@code SNOOZED} 가 있는 수
 * @param acted 그 가운데 {@code OPENED} 나 {@code ACTED} 가 있는 수
 * @param nowShown {@code NOW} 로 보인 항목 수
 * @param nowHiddenWithoutAction 그 가운데 {@code OPENED}, {@code ACTED} 없이 {@code HIDDEN} 이 있는 수
 * @param staleShown {@code SHOWN} 때 출처가 오래된 것이던 항목 수
 * @param medianSecondsToFirstAction 첫 {@code SHOWN} 에서 첫 {@code OPENED} 나 {@code ACTED} 까지 초의 중앙값. 없으면 null
 */
public record AttentionMetric(
        AttentionTrigger trigger,
        long shown,
        long hidden,
        long snoozed,
        long acted,
        long nowShown,
        long nowHiddenWithoutAction,
        long staleShown,
        Long medianSecondsToFirstAction) {}
