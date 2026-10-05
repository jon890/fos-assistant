package com.bifos.assistant.task.domain.type;

/** 예약 작업이 여는 실행의 종류다. */
public enum TaskKind {
    /** 일반 예약 대화 turn 이다. */
    TURN,
    /** 에이전트의 먼저 살펴보기 깨우기다. */
    CHECK
}
