package com.bifos.assistant.runtime.application.dto;

/** 끝난 실행이 어떻게 끝났는지다. 부르는 쪽이 분기에 쓴다. */
public enum RunOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLED
}
