package com.bifos.assistant.usage.execution.domain.type;

/** 실행 줄에 적힌 reasoning effort 의 출처다. */
public enum ReasoningEffortSource {
    REQUESTED,
    /** 에이전트 기본 effort 를 보냈다. 에이전트 기본 모델을 없앤 뒤로 새 실행에는 적지 않고 옛 기록에만 남는다. */
    AGENT_DEFAULT,
    PROFILE_DEFAULT,
    UNKNOWN
}
