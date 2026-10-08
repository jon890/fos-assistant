package com.bifos.assistant.context;

/**
 * 문맥 묶음의 항목이 어느 기록에서 왔는지다(ADR-071).
 *
 * <p>저장하지 않는다. 실행 기록에 남길 때는 이름을 문자열로 옮긴다.
 */
public enum ContextSource {
    /** 본문까지 싣는 Memory 항상 층의 줄. */
    MEMORY_ALWAYS,
    /** 본문까지 싣는 Memory 개인 사실 구역의 줄. */
    MEMORY_FACTS,
    /** 제목만 싣는 Memory 색인 층의 줄. */
    MEMORY_INDEX,
    /** memory_read 로 본문을 읽은 항목. 조립이 아니라 도구 처리가 실행 기록에 덧붙인다. */
    MEMORY_READ,
    /** 끝난 위임 실행의 결과. */
    DELEGATION_RESULT,
    /** 승인한 커넥터 동작의 결과. */
    CONNECTOR_RESULT,
    /** 에이전트 실행의 상태와 시각. */
    EXECUTION_STATE,
    /** 할 일. */
    FOLLOW_UP
}
