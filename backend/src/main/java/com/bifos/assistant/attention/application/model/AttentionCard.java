package com.bifos.assistant.attention.application.model;

import com.bifos.assistant.attention.domain.type.CardKey;
import java.util.List;

/**
 * 카드 하나다.
 *
 * @param nowCount 상한으로 자르기 전 이 카드의 {@code NOW} 항목 수
 * @param moreCount 상한을 넘어 빠진 항목 수
 */
public record AttentionCard(CardKey key, CardStatus status, int nowCount, int moreCount, List<AttentionItem> items) {}
