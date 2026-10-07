package com.bifos.assistant.feedback.domain.type;

/** 사건을 일으킨 쪽이다. 사용자의 반응만 {@code USER} 다. */
public enum FeedbackActor {
    /** 사용자가 단추나 API 로 정했다. */
    USER,
    /** 에이전트가 제안하거나 승인을 요청했다. */
    AGENT,
    /** Control Plane 이 보이거나 실행을 끝냈다. */
    SYSTEM
}
