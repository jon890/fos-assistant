package com.bifos.assistant.proactive.domain.type;

/** 문제 후보를 버린 까닭이다. 판정 순서와 조건은 {@code docs/backend/proactive-check.md} 의 「후보 검사」 가 갖는다. */
public enum ProblemDropReason {
    /** 문제 키, 문제, 행동, 기대 효과 가운데 빈 것이 있거나 정해진 값 밖의 값이 있다. */
    INCOMPLETE,
    /** 이 문제가 닿는 사용자의 목표나 맥락을 적지 않았다. 새 데이터만으로 문제를 만들지 않는다. */
    NO_GOAL,
    /** 검사를 통과한 같은 블록의 발견을 하나도 가리키지 않는다. */
    NO_EVIDENCE,
    /** 같은 블록이나 최근에 받아들인 후보와 문제 키가 같고 달라진 점을 적지 않았다. */
    DUPLICATE,
    /** 제안 행동이 그 사용자의 열린 할 일과 같은 제목이다. */
    EXISTING_FOLLOW_UP
}
