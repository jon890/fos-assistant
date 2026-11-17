package com.bifos.assistant.orchestration.application.dto;

/** 하위 에이전트 끝 소식을 받은 결과다(ADR-20261117 / native-delegation-wake). */
public enum SubagentStopResult {
    /** 결과를 등록 줄에 적었다. */
    RECORDED,
    /** 깨울 자식이 아니거나 이미 적었다. 다시 보낸 소식도 여기다. */
    IGNORED,
    /** 그 자식의 등록 줄이 없다. */
    NOT_FOUND
}
