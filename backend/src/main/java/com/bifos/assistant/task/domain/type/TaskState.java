package com.bifos.assistant.task.domain.type;

/**
 * 예약 작업의 상태다(ADR-071). DB 에 이름 그대로 저장되므로 값을 바꾸면 마이그레이션이 필요하다.
 *
 * <p>무엇이 바꾸는지는 {@code docs/backend/task.md} 의 「작업」 이 갖는다.
 */
public enum TaskState {
    /** 시각이 오면 발화한다. */
    ACTIVE,
    /** 발화하지 않는다. */
    PAUSED,
    /** 지운 작업이다. 목록과 발화에서 빠지고 줄은 남는다. */
    ARCHIVED
}
