package com.bifos.assistant.connector.connection.domain.type;

/**
 * 사건 감지 확인 한 번의 결과다(ADR-20261105 / event-watch). 바인딩 줄의 상태 JSON 에 이름 그대로 저장된다.
 *
 * <p>언제 붙는지는 {@code docs/features/proactive.md} 의 「사건 감지」 가 갖는다.
 */
public enum EventWatchResult {
    /** 새 항목이 없어 모델을 부르지 않았다. */
    NO_NEW,
    /** 새 항목으로 살펴보기를 열었다. */
    WOKE,
    /** 사용자나 점검 대화에 도는 turn 이 있어 다음 확인으로 넘겼다. */
    BUSY,
    /** 바인딩, 연결, 에이전트, 도구 조건 가운데 하나가 맞지 않아 깨우지 않았다. */
    BLOCKED,
    /** 그날의 사건 실행 상한에 닿았다. */
    DAILY_LIMIT,
    /** 목록을 받지 못했다. */
    FAILED
}
