package com.bifos.assistant.usage.application.model;

/** 실행 줄을 만들 때 사용자 실행 한도를 어떻게 볼지다(ADR-069). 저장하지 않는다. */
public enum ExecutionAdmission {
    /** 대화 turn 의 루트 줄. turn 자리가 이미 세었으므로 다시 보지 않는다 */
    TURN_ROOT,
    /** 흐름 단계와 위임 자식. 쥔 자리가 max-running 보다 작아야 한다 */
    CHILD,
    /** 매일 깨우기의 위임 자식. 사용자 대화를 위해 적어도 한 자리를 남긴다. */
    BACKGROUND_CHILD,
    /** 추천 질문과 Memory 제안. 줄을 만든 뒤에도 background-reserve 자리가 남아야 한다 */
    BACKGROUND
}
