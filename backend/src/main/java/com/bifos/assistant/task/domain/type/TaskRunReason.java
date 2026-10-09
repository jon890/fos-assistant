package com.bifos.assistant.task.domain.type;

/**
 * {@code SKIPPED} 와 {@code FAILED} 발화의 까닭, 그리고 알릴 것 없이 끝낸 {@code SUCCEEDED} 발화의 까닭이다. DB 에 이름 그대로
 * 저장된다.
 *
 * <p>언제 붙는지는 {@code backend/docs/flow.md} 의 「발화와 시작」 이 갖는다.
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
    /** 읽지 않은 보고가 있어 다음 깨우기를 건너뛰었다. */
    UNREAD_REPORT,
    /** turn 이 예외로 끝났다. */
    FAILED,
    /** 도는 중에 서버가 다시 시작됐다. */
    INTERRUPTED,
    /** 답 전체가 {@code [SILENT]} 라 알릴 것 없이 끝냈다. {@code SUCCEEDED} 발화에 붙는다. */
    NOTHING_TO_REPORT
}
