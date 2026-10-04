package com.bifos.assistant.attention.application.model;

import com.bifos.assistant.attention.domain.type.CardKey;
import java.time.Instant;

/**
 * 사용자가 한 카드의 한 항목에 건 숨기기나 미루기다.
 *
 * <p>제어는 카드마다 따로다. 같은 {@code itemKey} 가 두 카드에 쓰여도 한 카드의 제어가 다른 카드에 걸리지 않는다.
 *
 * @param stateKey 숨긴 상태의 지문. 숨기지 않았으면 null
 * @param snoozedUntil 미룬 기한. 미루지 않았으면 null
 */
public record AttentionControl(CardKey card, String itemKey, String stateKey, Instant snoozedUntil) {}
