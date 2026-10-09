package com.bifos.assistant.proactive.domain.type;

/** 발견을 「참고」 로 내린 까닭이다. 판정 순서와 조건은 {@code FindingJudgement} 가 갖는다. */
public enum FindingReason {
    /** 원문 주소가 {@code http} 나 {@code https} 의 절대 주소가 아니다. */
    NO_SOURCE,
    /** 원문을 확인한 시각을 읽지 못했거나 이번 살펴보기 동안이 아니다. */
    NOT_CHECKED_NOW,
    /** 이미 마감됐다. */
    CLOSED,
    /** 오래된 소식이다. */
    STALE,
    /** 지금도 유효한지 모른다. */
    FRESHNESS_UNKNOWN,
    /** 제목, 이유, 사실, 다음 행동 가운데 빠진 것이 있다. */
    INCOMPLETE,
    /** 최근에 같은 주제와 같은 원문을 이미 알렸다. */
    REPEATED
}
