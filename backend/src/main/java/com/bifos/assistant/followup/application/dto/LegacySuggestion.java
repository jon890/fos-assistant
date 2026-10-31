package com.bifos.assistant.followup.application.dto;

import java.time.Instant;

/**
 * 옛 발견이나 문제 후보를 할 일의 제안 줄로 옮길 때 넘기는 값이다(ADR-20261101 / proactive-suggestion).
 *
 * @param executionId 옛 줄을 낸 살펴보기의 루트 실행. 없으면 null
 * @param title 할 일 제목. 앞뒤 공백을 지운 뒤 {@code FollowUpService.TITLE_MAX} 자를 넘으면 그 길이까지 자른다
 * @param dedupeKey {@code legacy:<옛 테이블>:<옛 번호>}. 다시 옮겨도 줄이 하나이게 하는 열쇠다
 * @param detailJson 옛 분류 원문을 {@code legacy} 자유 필드에 담은 JSON. 없으면 null
 * @param createdAt 옛 줄이 생긴 시각. 제안 줄의 만든 시각이 되고, 만료 시각은 이 시각에서 센다
 */
public record LegacySuggestion(
    Long userId,
    Long conversationId,
    Long executionId,
    String title,
    String dedupeKey,
    String detailJson,
    Instant createdAt) {

    /** 제목과 본문을 빼고 낸다. 둘 다 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "LegacySuggestion[userId=" + userId + ", dedupeKey=" + dedupeKey + "]";
    }
}
