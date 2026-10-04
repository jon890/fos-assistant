package com.bifos.assistant.attention.application.model;

import com.bifos.assistant.attention.domain.type.CardKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 판정 한 번에 쓴 재료와 그 결과다. 지금 화면과 사용자 제어가 같은 계산을 함께 쓴다.
 *
 * @param now 판정 시각
 * @param candidates 카드마다 모은 억제 전 후보. 숨기기와 미루기는 이 목록에 있는 항목만 받는다
 * @param unavailable 원래 기록을 읽지 못한 카드
 * @param cards 요청자의 숨기기와 미루기를 적용해 판정한 카드 넷
 */
public record AttentionSnapshot(
        Instant now,
        Map<CardKey, List<AttentionCandidate>> candidates,
        Set<CardKey> unavailable,
        List<AttentionCard> cards) {}
