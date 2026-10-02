package com.bifos.assistant.usage.domain.type;

/** 실행 줄에 적힌 reasoning effort 의 출처다. */
public enum ReasoningEffortSource {
    REQUESTED,
    /** 대화도 단계도 고르지 않아 에이전트 기본 effort 를 보냈다(ADR-054). */
    AGENT_DEFAULT,
    PROFILE_DEFAULT,
    UNKNOWN
}
