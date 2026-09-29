package com.bifos.assistant.orchestration.application;

/** 하위 에이전트 session 등록의 결과다. 같은 origin 으로 다시 온 등록은 새 줄 없이 {@link #EXISTS} 다. */
public enum SubagentRegistrationResult {
    CREATED,
    EXISTS
}
