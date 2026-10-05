package com.bifos.assistant.attention.domain.type;

/** 항목의 판정이다. {@code SUPPRESSED} 는 응답에 넣지 않는다. 지표 사건이 이 이름을 저장한다. */
public enum AttentionLevel {
    NOW,
    LATER,
    SUPPRESSED
}
