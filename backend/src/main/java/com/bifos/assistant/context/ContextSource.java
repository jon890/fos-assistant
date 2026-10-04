package com.bifos.assistant.context;

/**
 * 문맥 묶음의 항목이 어느 기록에서 왔는지다(ADR-071).
 *
 * <p>저장하지 않는다. 실행 기록에 남길 때는 이름을 문자열로 옮긴다.
 */
public enum ContextSource {
    /** 본문까지 싣는 Memory 항상 층의 줄. */
    MEMORY_ALWAYS,
    /** 제목만 싣는 Memory 색인 층의 줄. */
    MEMORY_INDEX,
    /** 끝난 위임 실행의 결과. */
    DELEGATION_RESULT,
    /** 승인한 커넥터 동작의 결과. */
    CONNECTOR_RESULT,
    /** 에이전트 실행의 상태와 시각. */
    EXECUTION_STATE,
    /** 할 일. */
    FOLLOW_UP
}
