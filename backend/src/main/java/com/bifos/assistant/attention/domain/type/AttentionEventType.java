package com.bifos.assistant.attention.domain.type;

/** 지표 사건의 종류다. {@code attention_event.event_type} 이 이 이름을 저장한다. 뜻은 {@code docs/backend/attention.md} 의 「지표」 가 갖는다. */
public enum AttentionEventType {
    /** {@code GET /api/v1/attention} 응답에 실렸다. */
    SHOWN,
    /** 사용자가 항목을 열었다. */
    OPENED,
    /** 사용자가 항목의 단추로 동작했다. */
    ACTED,
    /** 사용자가 숨겼다. */
    HIDDEN,
    /** 사용자가 미뤘다. */
    SNOOZED
}
