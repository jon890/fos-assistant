package com.bifos.assistant.usage.domain;

/**
 * 실행 하나가 도는 동안 일어난 일을 우리 이름으로 적은 것이다.
 *
 * <p>Hermes 의 원래 사건 이름을 화면이 읽지 않게 하려고 둔 값이다. 근거는 ADR-013 에 있다.
 */
public enum ExecutionEventType {
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_CANCELLED,
    RUN_FAILED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    SUBAGENT_STARTED,
    SUBAGENT_COMPLETED,
    /**
     * 앞 provider 가 막혀 이 실행이 다음 모델로 다시 시도된 것이다.
     *
     * <p>{@code detail} 에 넘어간 곳의 provider 와 모델을 적는다. 막힌 쪽의 오류 글은 적지 않는다.
     * 상류가 보낸 문장이라 무엇이 들어올지 모른다.
     */
    PROVIDER_SWITCHED;

    /** 도구 호출 사건인가. 도구 이름을 채울 사건을 고를 때 쓴다. */
    public boolean isTool() {
        return this == TOOL_STARTED || this == TOOL_COMPLETED;
    }

    /** 하위 에이전트 사건인가. 하위 에이전트 이름을 채울 사건을 고를 때 쓴다. */
    public boolean isSubagent() {
        return this == SUBAGENT_STARTED || this == SUBAGENT_COMPLETED;
    }
}
