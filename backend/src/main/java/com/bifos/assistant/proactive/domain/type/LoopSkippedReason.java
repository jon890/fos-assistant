package com.bifos.assistant.proactive.domain.type;

/** 매일 루프가 깨우기를 평가하지 않고 건너뛴 까닭이다. */
public enum LoopSkippedReason {
    /** 사용자가 쉬기로 정한 시각 전이다. */
    SNOOZED,
    /** 그 살펴보기에 {@code ACCEPTED} 문제 후보가 없다. 모델을 부르지 않으므로 하루 상한에 세지 않는다. */
    NO_CANDIDATE,
    /** 그 사용자의 하루 시도 상한에 닿았다. */
    DAILY_LIMIT
}
