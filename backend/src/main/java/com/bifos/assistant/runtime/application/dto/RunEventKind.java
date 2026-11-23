package com.bifos.assistant.runtime.application.dto;

/**
 * 런타임이 실행 스트림으로 보내는 사건의 종류다.
 *
 * <p>런타임마다 사건 이름이 달라 구현이 이 종류로 옮긴다. 옮길 종류가 없는 사건은 {@link #OTHER} 이고 부르는 쪽은 버린다.
 */
public enum RunEventKind {
    MESSAGE_DELTA,
    MESSAGE_INTERIM,
    TOOL_STARTED,
    TOOL_COMPLETED,
    SUBAGENT_STARTED,
    SUBAGENT_COMPLETED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED,
    RUN_INTERRUPTED,
    OTHER;


    /** 실행이 끝났음을 알리는 사건인가. */
    public boolean terminal() {
        return this == RUN_COMPLETED || this == RUN_FAILED || this == RUN_CANCELLED || this == RUN_INTERRUPTED;
    }
}
