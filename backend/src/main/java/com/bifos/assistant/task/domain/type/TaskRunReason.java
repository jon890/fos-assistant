package com.bifos.assistant.task.domain.type;

/**
 * {@code SKIPPED} 와 {@code FAILED} 발화의 까닭이다. DB 에 이름 그대로 저장된다.
 *
 * <p>언제 붙는지는 {@code docs/backend/task.md} 의 「발화와 시작」 이 갖는다.
 */
public enum TaskRunReason {
    /** 놓친 발화를 {@code SKIP} 으로 건너뛰었다. */
    MISSED,
    /** 사용자의 하루 발화 상한에 닿았다. */
    DAILY_LIMIT,
    /** 시작하기 전에 작업을 멈추거나 지웠다. */
    PAUSED,
    /** 주인이 허용 목록에서 꺼졌다. */
    OWNER_REVOKED,
    /** 에이전트를 쓸 수 없다. */
    AGENT_UNAVAILABLE,
    /** 시작 기한 안에 turn 잠금을 얻지 못했다. */
    BUSY,
    /** turn 이 예외로 끝났다. */
    FAILED,
    /** 도는 중에 서버가 다시 시작됐다. */
    INTERRUPTED
}
